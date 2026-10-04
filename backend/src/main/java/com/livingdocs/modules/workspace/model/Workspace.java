package com.livingdocs.modules.workspace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A workspace is a tenant that groups users, repositories, documents
 * and drift analyses. Each workspace has exactly one owner (the user
 * that created it) but can have many members.
 */
@Entity
@Table(name = "workspaces")
public class Workspace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 140)
    private String slug;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Workspace() {
        // JPA
    }

    public Workspace(String name, String slug, String description, UUID ownerId) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.ownerId = ownerId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getOwnerId() { return ownerId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @jakarta.persistence.PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    // ---- Test helpers ----
    public void setIdForTest(UUID id) {
        try {
            var f = Workspace.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(this, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}