package com.livingdocs.modules.github.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One repository access scope the user granted to the LivingDocs GitHub
 * application.
 *
 * <p>The OAuth token itself carries the scopes, but the token is opaque
 * to the user: they cannot see what LivingDocs is allowed to do with
 * their account. This table is the reviewable record — the screen that
 * lists the scopes is built from it, and a revoked scope is marked with
 * {@code revokedAt} instead of being deleted, so the history of what
 * was granted and withdrawn stays visible.
 */
@Entity
@Table(
        name = "github_scope_grants",
        uniqueConstraints = @UniqueConstraint(name = "uq_github_scope_grants",
                columnNames = {"user_id", "scope"}),
        indexes = {
                @Index(name = "idx_github_scope_grants_user", columnList = "user_id")
        }
)
public class GithubScopeGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "scope", nullable = false, length = 120)
    private String scope;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private OffsetDateTime grantedAt;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    protected GithubScopeGrant() {
        // JPA
    }

    public GithubScopeGrant(UUID userId, String scope, String description) {
        this.userId = userId;
        this.scope = scope;
        this.description = description;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (grantedAt == null) grantedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getScope() { return scope; }
    public String getDescription() { return description; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }
    public OffsetDateTime getRevokedAt() { return revokedAt; }

    public boolean isActive() { return revokedAt == null; }
    public void setDescription(String description) { this.description = description; }
    public void revoke() { this.revokedAt = OffsetDateTime.now(); }
    public void grant() { this.revokedAt = null; }
}
