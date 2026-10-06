package com.livingdocs.modules.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A short-lived 6-digit OTP issued to verify a user's email address.
 *
 * <p>The raw code is never stored — only its BCrypt hash. Codes have a
 * limited number of attempts and a TTL (typically 15 minutes) before
 * they expire.
 */
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode {

    public enum Purpose {
        REGISTER,
        EMAIL_CHANGE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected EmailVerificationCode() {
        // JPA
    }

    public EmailVerificationCode(UUID userId, String codeHash, Purpose purpose, OffsetDateTime expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getCodeHash() { return codeHash; }
    public Purpose getPurpose() { return purpose; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void incrementAttempts() {
        this.attempts += 1;
    }

    public void markConsumed() {
        this.consumedAt = OffsetDateTime.now();
    }

    public boolean isExpired(OffsetDateTime now) {
        return expiresAt.isBefore(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
