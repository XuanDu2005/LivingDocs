package com.livingdocs.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal representing an authenticated LivingDocs user.
 *
 * <p>Carries the database user id alongside the standard email so that
 * controllers can resolve the current user without an extra database hit.
 * Also carries the user's active platform role codes (loaded from the
 * {@code user_roles} table at authentication time) so that
 * {@link com.livingdocs.common.security.PlatformRoleInterceptor} can
 * enforce {@link RequirePlatformRole} without an extra lookup.
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final List<String> roleCodes;

    public AuthenticatedUser(UUID id, String email, String passwordHash, boolean enabled) {
        this(id, email, passwordHash, enabled, List.of());
    }

    public AuthenticatedUser(UUID id, String email, String passwordHash, boolean enabled, List<String> roleCodes) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.roleCodes = roleCodes == null ? Collections.emptyList() : List.copyOf(roleCodes);
    }

    public UUID getId() {
        return id;
    }

    /**
     * @return immutable list of active platform role codes for this principal.
     *         Empty list means the user has no role assignments yet.
     */
    public List<String> getRoleCodes() {
        return roleCodes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>(roleCodes.size() + 1);
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        for (String code : roleCodes) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + code));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}