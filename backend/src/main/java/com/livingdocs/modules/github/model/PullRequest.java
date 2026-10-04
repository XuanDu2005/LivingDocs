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
 * Pull request metadata ingested from a connected repository.
 *
 * <p>The {@code (repository_id, github_pr_number)} unique constraint
 * makes ingestion idempotent — re-receiving the same PR via webhook
 * updates the row in place rather than duplicating it.
 */
@Entity
@Table(name = "pull_requests")
public class PullRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false, updatable = false)
    private UUID repositoryId;

    @Column(name = "github_pr_number", nullable = false, updatable = false)
    private Long githubPrNumber;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 24)
    private State state;

    @Column(name = "author_login", length = 120)
    private String authorLogin;

    @Column(name = "head_branch", nullable = false, length = 200)
    private String headBranch;

    @Column(name = "base_branch", nullable = false, length = 200)
    private String baseBranch;

    @Column(name = "head_sha", nullable = false, length = 80)
    private String headSha;

    @Column(name = "html_url", length = 500)
    private String htmlUrl;

    @Column(name = "is_draft", nullable = false)
    private boolean isDraft;

    @Column(name = "opened_at", nullable = false)
    private OffsetDateTime openedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "merged_at")
    private OffsetDateTime mergedAt;

    @Column(name = "last_ingested_at", nullable = false)
    private OffsetDateTime lastIngestedAt;

    public enum State { OPEN, CLOSED, MERGED }

    public static PullRequest create(UUID repositoryId, Long number, String title, State state,
                                     String authorLogin, String headBranch, String baseBranch,
                                     String headSha, String htmlUrl, boolean isDraft,
                                     OffsetDateTime openedAt, OffsetDateTime updatedAt,
                                     OffsetDateTime closedAt, OffsetDateTime mergedAt) {
        PullRequest p = new PullRequest();
        p.id = UuidGenerator.newId();
        p.repositoryId = repositoryId;
        p.githubPrNumber = number;
        p.title = title;
        p.state = state;
        p.authorLogin = authorLogin;
        p.headBranch = headBranch;
        p.baseBranch = baseBranch;
        p.headSha = headSha;
        p.htmlUrl = htmlUrl;
        p.isDraft = isDraft;
        p.openedAt = openedAt;
        p.updatedAt = updatedAt;
        p.closedAt = closedAt;
        p.mergedAt = mergedAt;
        p.lastIngestedAt = OffsetDateTime.now();
        return p;
    }

    public void updateFrom(State state, String title, String headSha, boolean isDraft,
                           OffsetDateTime updatedAt, OffsetDateTime closedAt,
                           OffsetDateTime mergedAt) {
        this.state = state;
        this.title = title;
        this.headSha = headSha;
        this.isDraft = isDraft;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
        this.mergedAt = mergedAt;
        this.lastIngestedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public Long getGithubPrNumber() { return githubPrNumber; }
    public String getTitle() { return title; }
    public State getState() { return state; }
    public String getAuthorLogin() { return authorLogin; }
    public String getHeadBranch() { return headBranch; }
    public String getBaseBranch() { return baseBranch; }
    public String getHeadSha() { return headSha; }
    public String getHtmlUrl() { return htmlUrl; }
    public boolean isDraft() { return isDraft; }
    public OffsetDateTime getOpenedAt() { return openedAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public OffsetDateTime getMergedAt() { return mergedAt; }
    public OffsetDateTime getLastIngestedAt() { return lastIngestedAt; }
}