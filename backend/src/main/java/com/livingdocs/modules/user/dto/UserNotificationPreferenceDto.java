package com.livingdocs.modules.user.dto;

import com.livingdocs.modules.user.model.UserNotificationPreference;

/**
 * View/edit projection of {@link UserNotificationPreference}. The wire
 * shape uses camelCase to match the rest of the API; the entity stores
 * snake_case for readability of the SQL row.
 */
public record UserNotificationPreferenceDto(
        boolean emailOnDriftDetected,
        boolean emailOnReviewRequested,
        boolean emailOnAutoUpdate,
        boolean emailOnTokenInvalid,
        boolean inAppOnDriftDetected,
        boolean inAppOnReviewRequested,
        boolean inAppOnAutoUpdate,
        boolean inAppOnTokenInvalid,
        String digestFrequency
) {
    public static UserNotificationPreferenceDto from(UserNotificationPreference p) {
        return new UserNotificationPreferenceDto(
                p.isEmailOnDriftDetected(),
                p.isEmailOnReviewRequested(),
                p.isEmailOnAutoUpdate(),
                p.isEmailOnTokenInvalid(),
                p.isInAppOnDriftDetected(),
                p.isInAppOnReviewRequested(),
                p.isInAppOnAutoUpdate(),
                p.isInAppOnTokenInvalid(),
                p.getDigestFrequency() == null ? "DAILY" : p.getDigestFrequency().name()
        );
    }
}
