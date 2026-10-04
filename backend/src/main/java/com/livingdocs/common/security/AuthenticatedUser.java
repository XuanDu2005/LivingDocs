package com.livingdocs.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Spring Security principal representing an authenticated LivingDocs user.
 *
 * <p>Carries the database user id alongside the standard email so that
 * controllers can resolve the current user without an extra database hit.
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;

    public AuthenticatedUser(UUID id, String email, String passwordHash, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
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