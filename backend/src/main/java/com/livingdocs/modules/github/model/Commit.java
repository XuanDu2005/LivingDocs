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
 * A single commit ingested from a connected GitHub repository. Used by:
 *   - the auto-update pipeline (each push webhook creates a Commit row that
 *     is later referenced from a DocumentVersion's sourceCommitSha);
 *   - the commit list UI on the Repository page;
 *   - drift analysis (a "drift" is a code change since the document was
 *     last updated, and we need commits to compute that window).
 *
 * <p>Uniqueness on (repository_id, sha) keeps the ingest idempotent when
 * GitHub redelivers the same push.
 */
@Entity
@Table(
        name = "commits",
        uniqueConstraints = @UniqueConstraint(name = "uq_commits_repo_sha",
                columnNames = {"repository_id", "sha"}),
        indexes = {
                @Index(name = "idx_commits_repo", columnList = "repository_id"),
                @Index(name = "idx_commits_authored_at", columnList = "authored_at DESC"),
                @Index(name = "idx_commits_author_email", columnList = "author_email")
        }
)
public class Commit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "sha", nullable = false, length = 80)
    private String sha;

    @Column(name = "message", length = 4000)
    private String message;

    @Column(name = "author_name", length = 200)
    private String authorName;

    @Column(name = "author_email", length = 200)
    private String authorEmail;

    @Column(name = "authored_at", nullable = false)
    private OffsetDateTime authoredAt;

    @Column(name = "committed_at")
    private OffsetDateTime committedAt;

    @Column(name = "url", length = 500)
    private String url;

    @Column(name = "branch", length = 200)
    private String branch;

    @Column(name = "ingested_at", nullable = false)
    private OffsetDateTime ingestedAt;

    protected Commit() {
        // JPA
    }

    public Commit(UUID repositoryId, String sha, String message, String authorName,
                  String authorEmail, OffsetDateTime authoredAt, OffsetDateTime committedAt,
                  String url, String branch) {
        this.repositoryId = repositoryId;
        this.sha = sha;
        this.message = message;
        this.authorName = authorName;
        this.authorEmail = authorEmail;
        this.authoredAt = authoredAt;
        this.committedAt = committedAt;
        this.url = url;
        this.branch = branch;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.ingestedAt == null) this.ingestedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public String getSha() { return sha; }
    public String getMessage() { return message; }
    public String getAuthorName() { return authorName; }
    public String getAuthorEmail() { return authorEmail; }
    public OffsetDateTime getAuthoredAt() { return authoredAt; }
    public OffsetDateTime getCommittedAt() { return committedAt; }
    public String getUrl() { return url; }
    public String getBranch() { return branch; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
}
