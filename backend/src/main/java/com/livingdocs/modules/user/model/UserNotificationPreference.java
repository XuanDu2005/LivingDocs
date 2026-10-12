package com.livingdocs.modules.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-user notification preferences. One row per user; created on first
 * access. The default values match the platform defaults (all in-app
 * notifications enabled, email digests at the {@code DAILY} frequency).
 */
@Entity
@Table(
        name = "user_notification_preferences",
        uniqueConstraints = @UniqueConstraint(name = "uq_user_notification_prefs_user",
                columnNames = {"user_id"}),
        indexes = {
                @Index(name = "idx_user_notification_prefs_user", columnList = "user_id")
        }
)
public class UserNotificationPreference {

    public enum DigestFrequency { REAL_TIME, DAILY, WEEKLY }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // Email channel
    @Column(name = "email_on_drift_detected", nullable = false)
    private boolean emailOnDriftDetected = true;

    @Column(name = "email_on_review_requested", nullable = false)
    private boolean emailOnReviewRequested = true;

    @Column(name = "email_on_auto_update", nullable = false)
    private boolean emailOnAutoUpdate = false;

    @Column(name = "email_on_token_invalid", nullable = false)
    private boolean emailOnTokenInvalid = true;

    // In-app channel
    @Column(name = "inapp_on_drift_detected", nullable = false)
    private boolean inAppOnDriftDetected = true;

    @Column(name = "inapp_on_review_requested", nullable = false)
    private boolean inAppOnReviewRequested = true;

    @Column(name = "inapp_on_auto_update", nullable = false)
    private boolean inAppOnAutoUpdate = true;

    @Column(name = "inapp_on_token_invalid", nullable = false)
    private boolean inAppOnTokenInvalid = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "digest_frequency", nullable = false, length = 16)
    private DigestFrequency digestFrequency = DigestFrequency.DAILY;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserNotificationPreference() {
        // JPA
    }

    public UserNotificationPreference(UUID userId) {
        this.userId = userId;
    }

    @jakarta.persistence.PrePersist
    @jakarta.persistence.PreUpdate
    void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public boolean isEmailOnDriftDetected() { return emailOnDriftDetected; }
    public void setEmailOnDriftDetected(boolean v) { this.emailOnDriftDetected = v; }
    public boolean isEmailOnReviewRequested() { return emailOnReviewRequested; }
    public void setEmailOnReviewRequested(boolean v) { this.emailOnReviewRequested = v; }
    public boolean isEmailOnAutoUpdate() { return emailOnAutoUpdate; }
    public void setEmailOnAutoUpdate(boolean v) { this.emailOnAutoUpdate = v; }
    public boolean isEmailOnTokenInvalid() { return emailOnTokenInvalid; }
    public void setEmailOnTokenInvalid(boolean v) { this.emailOnTokenInvalid = v; }
    public boolean isInAppOnDriftDetected() { return inAppOnDriftDetected; }
    public void setInAppOnDriftDetected(boolean v) { this.inAppOnDriftDetected = v; }
    public boolean isInAppOnReviewRequested() { return inAppOnReviewRequested; }
    public void setInAppOnReviewRequested(boolean v) { this.inAppOnReviewRequested = v; }
    public boolean isInAppOnAutoUpdate() { return inAppOnAutoUpdate; }
    public void setInAppOnAutoUpdate(boolean v) { this.inAppOnAutoUpdate = v; }
    public boolean isInAppOnTokenInvalid() { return inAppOnTokenInvalid; }
    public void setInAppOnTokenInvalid(boolean v) { this.inAppOnTokenInvalid = v; }
    public DigestFrequency getDigestFrequency() { return digestFrequency; }
    public void setDigestFrequency(DigestFrequency v) { this.digestFrequency = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
