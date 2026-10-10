package com.livingdocs.modules.document.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "document_tags", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"workspace_id", "name"})
})
public class DocumentTag {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "color_hex", length = 7)
    private String colorHex;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected DocumentTag() {
        // Dành cho JPA
    }

    public DocumentTag(UUID workspaceId, String name, String colorHex) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.colorHex = (colorHex != null && !colorHex.isBlank()) ? colorHex : "#808080";
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getName() { return name; }
    public String getColorHex() { return colorHex; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}