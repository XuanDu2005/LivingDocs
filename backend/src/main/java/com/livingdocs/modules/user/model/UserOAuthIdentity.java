package com.livingdocs.modules.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A linked OAuth identity (Google, GitHub) attached to a {@link User}.
 *
 * <p>One user can have multiple identities (e.g. both Google and GitHub).
 * Each row is unique on {@code (provider, providerUserId)} so the same
 * Google account cannot be linked to two different LivingDocs users.
 */
@Entity
@Table(name = "user_oauth_identities")
public class UserOAuthIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 128)
    private String providerUserId;

    @Column(name = "provider_email", length = 255)
    private String providerEmail;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(name = "access_token", length = 500)
    private String accessToken;

    @Column(name = "refresh_token", length = 500)
    private String refreshToken;

    @Column(name = "token_expires_at")
    private OffsetDateTime tokenExpiresAt;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private OffsetDateTime linkedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    protected UserOAuthIdentity() {
        // JPA
    }

    public UserOAuthIdentity(UUID userId, OAuthProvider provider, String providerUserId,
                             String providerEmail, String displayName, String avatarUrl) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.providerEmail = providerEmail;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.linkedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        // linkedAt is set once at creation; touch last_used_at instead.
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public OAuthProvider getProvider() { return provider; }
    public String getProviderUserId() { return providerUserId; }
    public String getProviderEmail() { return providerEmail; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public OffsetDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public OffsetDateTime getLinkedAt() { return linkedAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }

    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public void setTokenExpiresAt(OffsetDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public void setProviderEmail(String providerEmail) { this.providerEmail = providerEmail; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public void touchLastUsed() {
        this.lastUsedAt = OffsetDateTime.now();
    }
}
