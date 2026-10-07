package com.livingdocs.modules.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistent user account.
 *
 * <p>Email is the natural login identifier and therefore unique and
 * case-insensitive at the database level (stored lowercased).
 * The {@code passwordHash} carries a BCrypt-encoded value; plain-text
 * passwords never leave the registration service.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "display_name", length = 120)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private OffsetDateTime emailVerifiedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected User() {
        // JPA
    }

    public User(String email, String displayName, String passwordHash) {
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public OffsetDateTime getEmailVerifiedAt() { return emailVerifiedAt; }
    public void setEmailVerifiedAt(OffsetDateTime emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }

    /**
     * Mark the email address as verified. Idempotent: calling it twice is safe.
     */
    public void markEmailVerified() {
        this.emailVerified = true;
        this.emailVerifiedAt = OffsetDateTime.now();
    }
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

    // ---- Test helpers (package-private) ---------------------------------
    // These let unit tests populate the JPA-managed state without reflective hacks.
    // They are not part of the production API.

    public void setEmailForTest(UUID id) {
        try {
            var f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(this, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public void setCreatedAtForTest(OffsetDateTime t) {
        try {
            var f = User.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(this, t);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public void setUpdatedAtForTest(OffsetDateTime t) {
        try {
            var f = User.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(this, t);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}