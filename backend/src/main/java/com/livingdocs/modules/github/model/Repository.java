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
 * A repository that has been linked to a workspace.
 *
 * <p>Tracks the GitHub identity (owner/name/fullName) and operational
 * metadata (default branch, last sync). The
 * {@link Status} column is updated by webhook ingestion and manual
 * sync operations.
 */
@Entity
@Table(name = "repositories")
public class Repository {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "github_id", nullable = false, updatable = false)
    private Long githubId;

    @Column(name = "owner", nullable = false, length = 120)
    private String owner;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "full_name", nullable = false, length = 320)
    private String fullName;

    @Column(name = "default_branch", nullable = false, length = 120)
    private String defaultBranch = "main";

    @Column(name = "html_url", length = 500)
    private String htmlUrl;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private Status status = Status.CONNECTED;

    @Column(name = "connected_by", nullable = false, updatable = false)
    private UUID connectedBy;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    public enum Status {
        CONNECTED, ARCHIVED, ERROR, DISCONNECTED
    }

    public static Repository create(UUID workspaceId, Long githubId, String owner, String name,
                                    String defaultBranch, String htmlUrl, String description,
                                    boolean isPrivate, UUID connectedBy) {
        Repository r = new Repository();
        r.id = UuidGenerator.newId();
        r.workspaceId = workspaceId;
        r.githubId = githubId;
        r.owner = owner;
        r.name = name;
        r.fullName = owner + "/" + name;
        r.defaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch;
        r.htmlUrl = htmlUrl;
        r.description = description;
        r.isPrivate = isPrivate;
        r.connectedBy = connectedBy;
        r.connectedAt = OffsetDateTime.now();
        r.status = Status.CONNECTED;
        return r;
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public Long getGithubId() { return githubId; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getFullName() { return fullName; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getHtmlUrl() { return htmlUrl; }
    public String getDescription() { return description; }
    public boolean isPrivate() { return isPrivate; }
    public Status getStatus() { return status; }
    public UUID getConnectedBy() { return connectedBy; }
    public OffsetDateTime getConnectedAt() { return connectedAt; }
    public OffsetDateTime getLastSyncedAt() { return lastSyncedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }
    public void setDescription(String description) { this.description = description; }
    public void setLastSyncedAt(OffsetDateTime lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; }
}