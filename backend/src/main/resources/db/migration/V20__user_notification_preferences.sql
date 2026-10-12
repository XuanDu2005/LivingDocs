-- LivingDocs V20 — Per-user notification preferences.
--
-- One row per user, created lazily on first access. The schema mirrors
-- the UserNotificationPreference JPA entity.

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    id                            UUID PRIMARY KEY,
    user_id                       UUID NOT NULL,
    email_on_drift_detected       BOOLEAN NOT NULL DEFAULT TRUE,
    email_on_review_requested     BOOLEAN NOT NULL DEFAULT TRUE,
    email_on_auto_update          BOOLEAN NOT NULL DEFAULT FALSE,
    email_on_token_invalid        BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_on_drift_detected       BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_on_review_requested     BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_on_auto_update          BOOLEAN NOT NULL DEFAULT TRUE,
    inapp_on_token_invalid        BOOLEAN NOT NULL DEFAULT TRUE,
    digest_frequency              VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_notification_prefs_user UNIQUE (user_id),
    CONSTRAINT chk_user_notification_digest CHECK (digest_frequency IN ('REAL_TIME','DAILY','WEEKLY'))
);

CREATE INDEX IF NOT EXISTS idx_user_notification_prefs_user
    ON user_notification_preferences (user_id);

ALTER TABLE user_notification_preferences
    ADD CONSTRAINT fk_user_notification_prefs_user
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
