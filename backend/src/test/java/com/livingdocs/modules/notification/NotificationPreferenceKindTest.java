package com.livingdocs.modules.notification;

import com.livingdocs.modules.notification.model.NotificationKinds;
import com.livingdocs.modules.notification.service.NotificationPreferenceService;
import com.livingdocs.modules.notification.service.NotificationPreferenceService.Preference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-user notification toggles used to have no effect because the
 * notification kinds emitted by the call sites did not match the kinds
 * the preference layer knew about.
 */
class NotificationPreferenceKindTest {

    /**
     * The call sites spell the same event in more than one way. Every
     * spelling has to land on the same preference, otherwise the user
     * switches a toggle off and the notification keeps arriving.
     */
    @Test
    void mapsEverySpellingOfATokenInvalidEventOntoTheSamePreference() {
        assertEquals(Preference.TOKEN_INVALID,
                NotificationPreferenceService.forKind("github.tokenInvalid"));
        assertEquals(Preference.TOKEN_INVALID,
                NotificationPreferenceService.forKind("github.token.invalid"));
        assertEquals(Preference.TOKEN_INVALID,
                NotificationPreferenceService.forKind("TOKEN_INVALID"));
    }

    @Test
    void mapsEverySpellingOfAReviewRequestOntoTheSamePreference() {
        assertEquals(Preference.REVIEW_REQUESTED,
                NotificationPreferenceService.forKind("review.requested"));
        assertEquals(Preference.REVIEW_REQUESTED,
                NotificationPreferenceService.forKind("REVIEW_ASSIGNED"));
        assertEquals(Preference.REVIEW_REQUESTED,
                NotificationPreferenceService.forKind("review.assigned"));
    }

    @Test
    void mapsEverySpellingOfAnAutoUpdateOntoTheSamePreference() {
        assertEquals(Preference.AUTO_UPDATE,
                NotificationPreferenceService.forKind("doc.auto.updated"));
        assertEquals(Preference.AUTO_UPDATE,
                NotificationPreferenceService.forKind("DOC_AUTO_UPDATED"));
        assertEquals(Preference.AUTO_UPDATE,
                NotificationPreferenceService.forKind("auto.update"));
    }

    @Test
    void mapsDriftDetectionOntoItsPreference() {
        assertEquals(Preference.DRIFT_DETECTED,
                NotificationPreferenceService.forKind("drift.detected"));
        assertEquals(Preference.DRIFT_DETECTED,
                NotificationPreferenceService.forKind("DRIFT_DETECTED"));
    }

    /** A kind without a dedicated toggle is always delivered. */
    @Test
    void returnsNullForKindsTheUserCannotControl() {
        assertNull(NotificationPreferenceService.forKind("ADMIN_BROADCAST"));
        assertNull(NotificationPreferenceService.forKind("SCM_PUSH"));
        assertNull(NotificationPreferenceService.forKind(null));
    }

    /**
     * Every kind the platform emits has to be registered, otherwise it
     * cannot be switched off by an administrator and its per-user
     * preference has nowhere to show up.
     */
    @Test
    void registersEveryEmittedKind() {
        for (String kind : new String[]{
                NotificationKinds.GITHUB_TOKEN_INVALID,
                NotificationKinds.DOC_GENERATION_FAILED,
                NotificationKinds.DOC_AUTO_UPDATED,
                NotificationKinds.REVIEW_REQUESTED,
                NotificationKinds.SYSTEM_TEST}) {
            assertTrue(NotificationKinds.ALL.contains(kind),
                    kind + " is emitted but missing from NotificationKinds.ALL");
        }
    }
}
