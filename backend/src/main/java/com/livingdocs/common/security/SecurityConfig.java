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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtFilter,
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
                                                          "/api/v1/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/oauth/*/start",
                                                       "/api/v1/auth/oauth/*/callback",
                                                       "/api/v1/auth/oauth/*/sandbox-start").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
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
                // Place the JWT filter AFTER SecurityContextHolderFilter (so the
                // context is initialised) but BEFORE AnonymousAuthenticationFilter
                // (so that a real authentication does not get overwritten by an
                // anonymous one).
                .addFilterAfter(jwtFilter, org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterBefore(jwtFilter, org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Register the {@link PlatformRoleInterceptor} so that controllers
     * annotated with {@link RequirePlatformRole} are protected.
     */
    @Bean
    public WebMvcConfigurer platformRoleMvcConfigurer(PlatformRoleInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}