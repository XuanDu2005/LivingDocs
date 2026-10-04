package com.livingdocs.modules.github.model;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Stores the GitHub OAuth credentials for a single user.
 *
 * <p>A user has at most one {@code GithubConnection} — the unique
 * constraint on {@code user_id} enforces that. The {@code status}
 * column allows the user to soft-revoke a connection without losing
 * the historical record.
 */
@Entity
@Table(name = "github_connections")
public class GithubConnection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "github_user_id", nullable = false, updatable = false)
    private Long githubUserId;

    @Column(name = "github_login", nullable = false, length = 120)
    private String githubLogin;

    @Column(name = "access_token", nullable = false, length = 255)
    private String accessToken;

    @Column(name = "token_type", nullable = false, length = 40)
    private String tokenType = "bearer";

    @Column(name = "scope", length = 500)
    private String scope;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.ACTIVE;

    public enum Status {
        ACTIVE, REVOKED, EXPIRED
    }

    public static GithubConnection create(UUID userId, Long githubUserId, String githubLogin,
                                          String accessToken, String tokenType, String scope) {
        GithubConnection c = new GithubConnection();
        c.id = UuidGenerator.newId();
        c.userId = userId;
        c.githubUserId = githubUserId;
        c.githubLogin = githubLogin;
        c.accessToken = accessToken;
        c.tokenType = tokenType;
        c.scope = scope;
        c.connectedAt = OffsetDateTime.now();
        c.status = Status.ACTIVE;
        return c;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Long getGithubUserId() { return githubUserId; }
    public String getGithubLogin() { return githubLogin; }
    public String getAccessToken() { return accessToken; }
    public String getTokenType() { return tokenType; }
    public String getScope() { return scope; }
    public OffsetDateTime getConnectedAt() { return connectedAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public OffsetDateTime getLastSyncedAt() { return lastSyncedAt; }
    public Status getStatus() { return status; }

    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public void setScope(String scope) { this.scope = scope; }
    public void setLastUsedAt(OffsetDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
    public void setStatus(Status status) { this.status = status; }
}