-- LivingDocs V32 — remove Zalo OA notification channel.
--
-- NOTE: the Zalo OA feature (zalo_* columns, ZaloNotifier listener, ZALO
-- integration provider) was reverted. This migration drops the columns
-- added by the original V32 so the schema matches the reverted code.
-- It is idempotent: IF EXISTS guards each DROP, so it is safe on a
-- database that never ran the original migration.
ALTER TABLE user_notification_preferences
    DROP COLUMN IF EXISTS zalo_on_drift_detected,
    DROP COLUMN IF EXISTS zalo_on_review_requested,
    DROP COLUMN IF EXISTS zalo_on_auto_update,
    DROP COLUMN IF EXISTS zalo_on_token_invalid,
    DROP COLUMN IF EXISTS zalo_user_id;
