package com.livingdocs.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Long-lived refresh token used to mint new short-lived access tokens
 * without forcing the user to log in again. Each row is single-use
 * (rotation): when a refresh is exchanged for a new access token, the
 * row is deleted and a new one is created.
 *
 * <p>Logout simply deletes all (or one) of these rows. Tokens are stored
 * as SHA-256 hashes of the raw token string so the database is not a
 * direct credential store.
 */
@Entity
@Table(
        name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_refresh_tokens_user", columnList = "user_id"),
                @Index(name = "idx_refresh_tokens_token_hash", columnList = "token_hash"),
                @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
        }
)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 128)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "user_agent", length = 256)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(UUID userId, String tokenHash, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getUserAgent() { return userAgent; }
    public String getIpAddress() { return ipAddress; }

    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
}
