package com.livingdocs.common.security;

import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Security configuration.
 *
 * <p>Stateless JWT-based security. CSRF is disabled because there is no
 * browser cookie-based session. The JWT filter runs before the standard
 * username/password filter.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService uds,
                                                                PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(uds);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService,
                                                           UserRepository userRepository,
                                                           UserRoleAssignmentRepository roleAssignmentRepository) {
        return new JwtAuthenticationFilter(jwtService, userRepository, roleAssignmentRepository);
    }

    /**
     * The filter that authenticates a CI job presenting a workspace CI
     * token.
     *
     * <p>It runs before the JWT filter so a CI request — which carries
     * no user session — is recognised as such. The filter binds the
     * authenticated workspace to the request; it does not establish a
     * {@code SecurityContext}, because a CI token is not a user.
     */
    @Bean
    public CiTokenAuthenticationFilter ciTokenAuthenticationFilter(
            com.livingdocs.modules.workspace.service.WorkspaceCiTokenService ciTokenService) {
        return new CiTokenAuthenticationFilter(ciTokenService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
                                                   CiTokenAuthenticationFilter ciTokenFilter,
                                                   CorsFilter corsFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        // Open endpoints
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/verify-email",
                                                          "/api/v1/auth/resend-verification",
                                                          "/api/v1/auth/forgot-password",
                                                          "/api/v1/auth/reset-password",
                                                          "/api/v1/auth/refresh",
                                                          "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/oauth/*/start",
                                                         "/api/v1/auth/oauth/*/callback",
                                                         "/api/v1/auth/oauth/*/sandbox-start").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/oauth/providers").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/oauth/*/link/start").permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/oauth/*/link").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        // Avatar images are public so that <img src="..."> works
                        // without attaching an Authorization header.
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/avatar/*/*").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // Webhook endpoint is authenticated via HMAC signature, not JWT.
                        .requestMatchers(HttpMethod.POST, "/api/v1/github/webhook").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/github/oauth/callback").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/github/oauth/complete").permitAll()
                        // The merge-gate endpoint a CI job calls. It is authenticated
                        // with the workspace CI token, which the controller verifies
                        // against the workspace in the path — so the token can never
                        // act outside the workspace it belongs to.
                        .requestMatchers(HttpMethod.GET, "/api/v1/cicd/**").permitAll()

                        // Integrations module: each provider verifies the request with its
                        // own mechanism (HMAC, shared token, signed timestamp), not JWT.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/integrations/webhook/github",
                                "/api/v1/integrations/webhook/gitlab",
                                "/api/v1/integrations/webhook/jira",
                                "/api/v1/integrations/webhook/slack").permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(corsFilter, org.springframework.web.filter.CorsFilter.class)
                .addFilterAfter(jwtFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(jwtFilter, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class)
                // The CI token filter runs ahead of the JWT filter: a CI request
                // carries a token header, not a user session, and the two
                // mechanisms never overlap.
                .addFilterBefore(ciTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Register the {@link PlatformRoleInterceptor} and the
     * {@link PermissionInterceptor} so that controllers annotated with
     * {@link RequirePlatformRole} / {@link RequirePermission} are
     * protected.
     *
     * <p>Both are registered in a single configurer so their order is
     * deterministic: the role guard runs first, so a role-guard failure
     * reports the role requirement rather than a permission code.
     */
    @Bean
    public WebMvcConfigurer authorizationMvcConfigurer(PlatformRoleInterceptor roleInterceptor,
                                                       PermissionInterceptor permissionInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(roleInterceptor);
                registry.addInterceptor(permissionInterceptor);
            }
        };
    }
}