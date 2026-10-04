package com.livingdocs.modules.code.model;

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
 * Snapshot of a code entity extracted by the AST analyser.
 */
@Entity
@Table(
        name = "code_entities",
        uniqueConstraints = @UniqueConstraint(name = "uq_code_entities",
                columnNames = {"repository_id", "commit_sha", "qualified_name", "file_path", "start_line"}),
        indexes = {
                @Index(name = "idx_code_entities_repo", columnList = "repository_id"),
                @Index(name = "idx_code_entities_commit", columnList = "commit_sha"),
                @Index(name = "idx_code_entities_qname", columnList = "qualified_name"),
                @Index(name = "idx_code_entities_type", columnList = "entity_type")
        }
)
public class CodeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "commit_sha", nullable = false, length = 80)
    private String commitSha;

    @Column(name = "language", nullable = false, length = 40)
    private String language;

    @Column(name = "entity_type", nullable = false, length = 40)
    private String entityType;

    @Column(name = "qualified_name", nullable = false, length = 500)
    private String qualifiedName;

    @Column(name = "simple_name", nullable = false, length = 200)
    private String simpleName;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "start_line", nullable = false)
    private Integer startLine;

    @Column(name = "end_line", nullable = false)
    private Integer endLine;

    @Column(name = "signature", columnDefinition = "text")
    private String signature;

    @Column(name = "docstring", columnDefinition = "text")
    private String docstring;

    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private OffsetDateTime ingestedAt;

    protected CodeEntity() {
        // JPA
    }

    public CodeEntity(UUID repositoryId, String commitSha, String language, String entityType,
                      String qualifiedName, String simpleName, String filePath,
                      Integer startLine, Integer endLine,
                      String signature, String docstring, String metadata) {
        this.repositoryId = repositoryId;
        this.commitSha = commitSha;
        this.language = language;
        this.entityType = entityType;
        this.qualifiedName = qualifiedName;
        this.simpleName = simpleName;
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.signature = signature;
        this.docstring = docstring;
        this.metadata = metadata;
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (this.ingestedAt == null) this.ingestedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public String getCommitSha() { return commitSha; }
    public String getLanguage() { return language; }
    public String getEntityType() { return entityType; }
    public String getQualifiedName() { return qualifiedName; }
    public String getSimpleName() { return simpleName; }
    public String getFilePath() { return filePath; }
    public Integer getStartLine() { return startLine; }
    public Integer getEndLine() { return endLine; }
    public String getSignature() { return signature; }
    public String getDocstring() { return docstring; }
    public String getMetadata() { return metadata; }
    public OffsetDateTime getIngestedAt() { return ingestedAt; }
}