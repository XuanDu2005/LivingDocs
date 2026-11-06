package com.livingdocs.modules.review.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One review pass on a document version.
 *
 * <p>{@code reviewerRole} is the platform role the reviewer acted under
 * (Staff or Manager). The same version may receive multiple review rows
 * (e.g. Staff approves, then Manager approves) — the latest entry per
 * {@code (versionId, reviewerRole)} is the authoritative verdict.
 *
 * <p>The three {@code section*} fields anchor a comment to one specific
 * part of the document (requirement #8 of the STAFF role: "comment /
 * request changes on a specific section"). A document is markdown, so a
 * section is a markdown heading; the reviewer picks the heading and the
 * backend stores its slug, its text and a GitHub-style anchor. They stay
 * {@code null} for a review that comments on the document as a whole.
 */
@Entity
@Table(
        name = "document_reviews",
        indexes = {
                @Index(name = "idx_document_reviews_version", columnList = "document_version_id"),
                @Index(name = "idx_document_reviews_reviewer", columnList = "reviewer_user_id")
        }
)
public class DocumentReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "reviewer_user_id", nullable = false)
    private UUID reviewerUserId;

    @Column(name = "reviewer_role", nullable = false, length = 16)
    private String reviewerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 24)
    private ReviewDecision decision;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    /** Slug of the markdown heading the comment targets, e.g. {@code overview}. */
    @Column(name = "section_id", length = 120)
    private String sectionId;

    /** The heading text as it appears in the document, e.g. {@code Overview}. */
    @Column(name = "section_heading", length = 255)
    private String sectionHeading;

    /** GitHub-style anchor for deep-linking, e.g. {@code #overview}. */
    @Column(name = "section_anchor", length = 255)
    private String sectionAnchor;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private OffsetDateTime decidedAt;

    /**
     * When this verdict was replaced by a later one in the same tier.
     *
     * <p>{@code null} means the verdict is still the current opinion of
     * its tier. A non-null value means it was superseded — the row is
     * kept for the trail, but it no longer counts as the authoritative
     * verdict.
     *
     * <p>This exists because a verdict is not immutable in practice: a
     * reviewer who mis-clicks "Reject" has to be able to correct the
     * mistake, and the workflow has to reflect the corrected opinion.
     * Before this column the only way to record a second opinion in the
     * same tier was to insert a second row, which the partial unique
     * index rejected with a constraint violation — the request failed
     * with a 500 instead of recording the correction.
     *
     * <p>The trail stays append-only: the superseded row is never
     * deleted, so a reader can still see what was said first.
     */
    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    protected DocumentReview() {
        // JPA
    }

    public DocumentReview(UUID documentVersionId, UUID reviewerUserId,
                          String reviewerRole, ReviewDecision decision, String comment) {
        this(documentVersionId, reviewerUserId, reviewerRole, decision, comment, null, null, null);
    }

    public DocumentReview(UUID documentVersionId, UUID reviewerUserId,
                          String reviewerRole, ReviewDecision decision, String comment,
                          String sectionId, String sectionHeading, String sectionAnchor) {
        this.documentVersionId = documentVersionId;
        this.reviewerUserId = reviewerUserId;
        this.reviewerRole = reviewerRole;
        this.decision = decision;
        this.comment = comment;
        this.sectionId = sectionId;
        this.sectionHeading = sectionHeading;
        this.sectionAnchor = sectionAnchor;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.decidedAt == null) this.decidedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public String getReviewerRole() { return reviewerRole; }
    public ReviewDecision getDecision() { return decision; }
    public String getComment() { return comment; }
    public String getSectionId() { return sectionId; }
    public String getSectionHeading() { return sectionHeading; }
    public String getSectionAnchor() { return sectionAnchor; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public OffsetDateTime getSupersededAt() { return supersededAt; }

    /**
     * Whether this verdict is still the current opinion of its tier.
     */
    public boolean isCurrent() { return supersededAt == null; }

    /**
     * Mark this verdict as replaced by a later one.
     *
     * <p>Idempotent: a verdict that is already superseded keeps its
     * original stamp, so the trail shows when the opinion was actually
     * replaced rather than when it was last touched.
     */
    public void supersede() {
        if (this.supersededAt == null) {
            this.supersededAt = OffsetDateTime.now();
        }
    }
}