package com.livingdocs.modules.notification.service;

import com.livingdocs.modules.user.model.UserNotificationPreference;
import com.livingdocs.modules.user.repository.UserNotificationPreferenceRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the per-user notification preferences for a notification kind.
 *
 * <p>The preferences screen stores eight flags (four events × two
 * channels) on {@code user_notification_preferences}. Until now
 * nothing read them, so the toggles had no effect — the user turned a
 * notification off and it kept arriving.
 *
 * <p>This service is the single place that maps a notification kind onto
 * the right flag, so the callers do not have to know the storage layout.
 * A kind that has no dedicated flag (an admin broadcast, an indexing
 * job) is always delivered — the user has no control over those.
 */
@Service
public class NotificationPreferenceService {

    private static final Logger log =
            LoggerFactory.getLogger(NotificationPreferenceService.class);

    /**
     * The event families a user can switch on and off.
     */
    public enum Preference {
        DRIFT_DETECTED,
        REVIEW_REQUESTED,
        AUTO_UPDATE,
        TOKEN_INVALID
    }

    private final UserNotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public NotificationPreferenceService(UserNotificationPreferenceRepository preferenceRepository,
                                         UserRepository userRepository) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    /**
     * Map a notification kind onto the preference it belongs to.
     *
     * <p>Returns {@code null} for kinds the user cannot influence.
     *
     * <p>The comparison is deliberately lenient about spelling: the
     * call sites use slightly different literals for the same event
     * ({@code "github.tokenInvalid"} vs {@code "github.token.invalid"},
     * {@code "review.requested"} vs {@code "REVIEW_ASSIGNED"}), and a
     * toggle that does not match the kind it is meant to control is
     * worse than no toggle at all — the user switches it off and the
     * notification keeps arriving.
     */
    public static Preference forKind(String kind) {
        if (kind == null) {
            return null;
        }
        String normalized = kind.trim().toLowerCase(Locale.ROOT)
                .replace('_', '.')
                .replace("-", ".");
        return switch (normalized) {
            case "drift.detected", "drift" -> Preference.DRIFT_DETECTED;
            case "review.requested", "review.assigned", "review" ->
                    Preference.REVIEW_REQUESTED;
            // The verdict on your own submission belongs to the same
            // family — a user who switched "review" off does not want
            // either message.
            case "review.decided" -> Preference.REVIEW_REQUESTED;
            case "doc.auto.updated", "auto.update", "auto.updated" ->
                    Preference.AUTO_UPDATE;
            case "github.token.invalid", "token.invalid", "github.tokeninvalid" ->
                    Preference.TOKEN_INVALID;
            default -> null;
        };
    }

    /**
     * The preferences of a user, or the platform defaults when the user
     * has never opened the preferences screen.
     */
    @Transactional(readOnly = true)
    public UserNotificationPreference preferencesOf(UUID userId) {
        if (userId == null) {
            return new UserNotificationPreference(UUID.randomUUID());
        }
        return preferenceRepository.findByUserId(userId)
                .orElseGet(() -> new UserNotificationPreference(userId));
    }

    /**
     * Whether the user wants this notification in the in-app bell.
     *
     * <p>Kinds without a dedicated preference are always delivered.
     */
    @Transactional(readOnly = true)
    public boolean isInAppEnabled(UUID userId, String kind) {
        Preference pref = forKind(kind);
        if (pref == null) {
            return true;
        }
        UserNotificationPreference row = preferencesOf(userId);
        return switch (pref) {
            case DRIFT_DETECTED -> row.isInAppOnDriftDetected();
            case REVIEW_REQUESTED -> row.isInAppOnReviewRequested();
            case AUTO_UPDATE -> row.isInAppOnAutoUpdate();
            case TOKEN_INVALID -> row.isInAppOnTokenInvalid();
        };
    }

    /**
     * Whether the user wants this notification by email.
     */
    @Transactional(readOnly = true)
    public boolean isEmailEnabled(UUID userId, String kind) {
        Preference pref = forKind(kind);
        if (pref == null) {
            return false;
        }
        UserNotificationPreference row = preferencesOf(userId);
        return switch (pref) {
            case DRIFT_DETECTED -> row.isEmailOnDriftDetected();
            case REVIEW_REQUESTED -> row.isEmailOnReviewRequested();
            case AUTO_UPDATE -> row.isEmailOnAutoUpdate();
            case TOKEN_INVALID -> row.isEmailOnTokenInvalid();
        };
    }

    /**
     * The email address of a user, when they have a verified one.
     */
    @Transactional(readOnly = true)
    public Optional<String> emailOf(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId)
                .map(u -> u.getEmail())
                .filter(e -> e != null && !e.isBlank());
    }

    /**
     * The digest frequency the user picked — used to decide whether a
     * non-urgent notification is sent right away or batched.
     */
    @Transactional(readOnly = true)
    public UserNotificationPreference.DigestFrequency digestFrequency(UUID userId) {
        return preferencesOf(userId).getDigestFrequency();
    }
}
