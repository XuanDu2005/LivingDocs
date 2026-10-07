package com.livingdocs.modules.auth.model;

import com.livingdocs.modules.user.model.OAuthProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Short-lived CSRF state used during the OAuth start/callback round-trip.
 *
 * <p>Created when the user clicks "Continue with Google/GitHub" and
 * consumed (deleted) when the provider redirects them back. The state
 * is a random 32-byte hex string stored verbatim.
 */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

    public enum Action {
        LOGIN,
        REGISTER,
        LINK
    }

    @Id
    @Column(name = "state_key", nullable = false, length = 64, updatable = false)
    private String stateKey;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private OAuthProvider provider;

    @Column(name = "redirect_after", length = 500)
    private String redirectAfter;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    protected OAuthState() {
        // JPA
    }

    public OAuthState(String stateKey, UUID userId, Action action, OAuthProvider provider,
                      String redirectAfter, OffsetDateTime expiresAt) {
        this.stateKey = stateKey;
        this.userId = userId;
        this.action = action;
        this.provider = provider;
        this.redirectAfter = redirectAfter;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public String getStateKey() { return stateKey; }
    public UUID getUserId() { return userId; }
    public Action getAction() { return action; }
    public OAuthProvider getProvider() { return provider; }
    public String getRedirectAfter() { return redirectAfter; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt.isBefore(now);
    }
}
