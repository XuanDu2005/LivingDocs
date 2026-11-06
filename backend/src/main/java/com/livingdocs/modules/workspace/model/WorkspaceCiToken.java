package com.livingdocs.modules.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The long-lived token a CI job presents to LivingDocs.
 *
 * <p>The merge gate is enforced by the CI job calling back into the
 * platform ("may this pull request be merged?"). A workflow has no user
 * session, so it cannot present a JWT — it presents this token
 * instead.
 *
 * <p>The token is:
 * <ul>
 *     <li><b>scoped</b> — it only ever authenticates a request for the
 *         one workspace that owns the row;</li>
 *     <li><b>opaque</b> — it carries no claims, the workspace is looked
 *         up from the row;</li>
 *     <li><b>hashed at rest</b> — the column holds the SHA-256 of the
 *         token, never the token itself, because the platform only ever
 *         needs to <em>verify</em> a token, never read it back;</li>
 *     <li><b>rotatable</b> — a rotation replaces the hash in place,
 *         which immediately invalidates the secret that GitHub holds.</li>
 * </ul>
 */
@Entity
@Table(name = "workspace_ci_tokens")
public class WorkspaceCiToken {

    @Id
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    /** Hex-encoded SHA-256 of the token. The plaintext is never stored. */
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    protected WorkspaceCiToken() {
        // JPA
    }

    public WorkspaceCiToken(UUID workspaceId, String tokenHash) {
        this.workspaceId = workspaceId;
        this.tokenHash = tokenHash;
        this.issuedAt = OffsetDateTime.now();
    }

    public UUID getWorkspaceId() { return workspaceId; }
    public String getTokenHash() { return tokenHash; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime at) { this.lastUsedAt = at; }

    /**
     * Replace the hash — used when the token is rotated. The plaintext
     * is not stored, so this is the only way the stored value ever
     * changes.
     */
    public void setTokenHash(String hash) { this.tokenHash = hash; }
}
