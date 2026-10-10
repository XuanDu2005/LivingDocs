package com.livingdocs.modules.admin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Platform permission. The {@code code} column is the canonical identifier
 * (e.g. DOCUMENT_CREATE, ADMIN_USER_MANAGE) and is what the application
 * code references when gating an action. Seeds are inserted by Flyway
 * migration V16.
 *
 * <p>{@code category} groups permissions in the admin UI matrix so the
 * operator sees coherent rows (workspace, document, AI, ...). Display
 * order controls the row order within a category.
 */
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 100;

    @Column(name = "is_system", nullable = false)
    private boolean system = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Permission() {
        // JPA
    }

    public Permission(UUID id, String code, String name, String description,
                      String category, int displayOrder, boolean system) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.category = category;
        this.displayOrder = displayOrder;
        this.system = system;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isSystem() { return system; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
