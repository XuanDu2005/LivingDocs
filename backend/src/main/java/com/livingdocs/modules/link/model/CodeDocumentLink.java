package com.livingdocs.modules.link.model;

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
 * Many-to-many link between a document and a code entity. Used to power
 * the knowledge base, the "navigate between code and doc" UI and the
 * impact analysis.
 */
@Entity
@Table(
        name = "code_document_links",
        uniqueConstraints = @UniqueConstraint(name = "uq_code_document_link",
                columnNames = {"document_id", "code_entity_id"}),
        indexes = {
                @Index(name = "idx_code_document_links_doc", columnList = "document_id"),
                @Index(name = "idx_code_document_links_entity", columnList = "code_entity_id")
        }
)
public class CodeDocumentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "code_entity_id", nullable = false)
    private UUID codeEntityId;

    @Column(name = "link_kind", nullable = false, length = 40)
    private String linkKind;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected CodeDocumentLink() {
        // JPA
    }

    public CodeDocumentLink(UUID documentId, UUID codeEntityId, String linkKind, Float confidence) {
        this.documentId = documentId;
        this.codeEntityId = codeEntityId;
        this.linkKind = linkKind == null ? "REFERENCE" : linkKind;
        this.confidence = confidence;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.createdAt == null) this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCodeEntityId() { return codeEntityId; }
    public String getLinkKind() { return linkKind; }
    public Float getConfidence() { return confidence; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}