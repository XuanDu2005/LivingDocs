package com.livingdocs.modules.user.service;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.user.dto.UserNotificationPreferenceDto;
import com.livingdocs.modules.user.model.UserNotificationPreference;
import com.livingdocs.modules.user.repository.UserNotificationPreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads and updates the per-user notification preferences. Lazily creates
 * a row with platform defaults the first time a user opens the page.
 */
@Service
public class UserNotificationPreferenceService {

    private final UserNotificationPreferenceRepository repository;

    public UserNotificationPreferenceService(UserNotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public UserNotificationPreferenceDto getMine() {
        return UserNotificationPreferenceDto.from(loadOrCreate(CurrentUser.requireId()));
    }

    @Transactional
    public UserNotificationPreferenceDto updateMine(UserNotificationPreferenceDto patch) {
        UserNotificationPreference row = loadOrCreate(CurrentUser.requireId());
        row.setEmailOnDriftDetected(patch.emailOnDriftDetected());
        row.setEmailOnReviewRequested(patch.emailOnReviewRequested());
        row.setEmailOnAutoUpdate(patch.emailOnAutoUpdate());
        row.setEmailOnTokenInvalid(patch.emailOnTokenInvalid());
        row.setInAppOnDriftDetected(patch.inAppOnDriftDetected());
        row.setInAppOnReviewRequested(patch.inAppOnReviewRequested());
        row.setInAppOnAutoUpdate(patch.inAppOnAutoUpdate());
        row.setInAppOnTokenInvalid(patch.inAppOnTokenInvalid());
        try {
            row.setDigestFrequency(UserNotificationPreference.DigestFrequency.valueOf(
                    patch.digestFrequency() == null ? "DAILY" : patch.digestFrequency()));
        } catch (IllegalArgumentException e) {
            row.setDigestFrequency(UserNotificationPreference.DigestFrequency.DAILY);
        }
        UserNotificationPreference saved = repository.save(row);
        return UserNotificationPreferenceDto.from(saved);
    }

    @Transactional
    public UserNotificationPreference loadOrCreate(UUID userId) {
        return repository.findByUserId(userId).orElseGet(() ->
                repository.save(new UserNotificationPreference(userId))
        );
    }
}
