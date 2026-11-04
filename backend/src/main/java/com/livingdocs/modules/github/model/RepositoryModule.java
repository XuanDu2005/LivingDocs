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
 * A module (directory / package) inside a repository whose documents can
 * be excluded from AI auto-update.
 *
 * <p>The repository toggle ({@link Repository#isAutoUpdateEnabled()}) and
 * the document toggle ({@code Document.autoUpdateEnabled}) bracket this
 * one: a workspace can turn auto-update off for the whole repository, or
 * for one package inside it, while the rest keeps updating.
 *
 * <p>The module is identified by its path prefix relative to the
 * repository root, normalised to start with {@code /} and to carry no
 * trailing {@code /} — so {@code /src/auth} and {@code src/auth/} address
 * the same module.
 */
@Entity
@Table(
        name = "repository_modules",
        uniqueConstraints = @UniqueConstraint(name = "uq_repository_modules_path",
                columnNames = {"repository_id", "module_path"}),
        indexes = {
                @Index(name = "idx_repository_modules_repo", columnList = "repository_id"),
                @Index(name = "idx_repository_modules_workspace", columnList = "workspace_id")
        }
)
public class RepositoryModule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    /** Normalised path prefix, e.g. {@code /src/auth}. */
    @Column(name = "module_path", nullable = false, length = 500)
    private String modulePath;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "auto_update_enabled", nullable = false)
    private boolean autoUpdateEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RepositoryModule() {
        // JPA
    }

    public RepositoryModule(UUID repositoryId, UUID workspaceId, String modulePath,
                            String displayName, boolean autoUpdateEnabled) {
        this.repositoryId = repositoryId;
        this.workspaceId = workspaceId;
        this.modulePath = modulePath;
        this.displayName = displayName;
        this.autoUpdateEnabled = autoUpdateEnabled;
    }

    /**
     * Normalise a caller-supplied module path.
     *
     * <p>Leading slashes are added, trailing slashes removed, and
     * consecutive slashes collapsed, so all the spellings of the same
     * directory address the same row.
     */
    public static String normalizePath(String raw) {
        if (raw == null) {
            return "/";
        }
        String p = raw.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "/" : p;
    }

    /**
     * Does a changed file path belong to this module?
     *
     * <p>The module {@code /src/auth} covers {@code src/auth/Token.java}
     * but not {@code src/authentication/User.java} — the match is on a
     * path segment boundary, not a bare string prefix.
     */
    public boolean covers(String filePath) {
        if (filePath == null || filePath.isBlank() || modulePath == null) {
            return false;
        }
        String normalizedFile = filePath.replace('\\', '/');
        while (normalizedFile.startsWith("/")) {
            normalizedFile = normalizedFile.substring(1);
        }
        String prefix = modulePath.startsWith("/") ? modulePath.substring(1) : modulePath;
        if (prefix.isEmpty()) {
            // The repository root covers everything.
            return true;
        }
        return normalizedFile.equals(prefix)
                || normalizedFile.startsWith(prefix + "/");
    }

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @jakarta.persistence.PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getWorkspaceId() { return workspaceId; }
    public String getModulePath() { return modulePath; }
    public String getDisplayName() { return displayName; }
    public boolean isAutoUpdateEnabled() { return autoUpdateEnabled; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setAutoUpdateEnabled(boolean enabled) { this.autoUpdateEnabled = enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
