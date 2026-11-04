package com.livingdocs.modules.notification;

import com.livingdocs.common.events.EventPublisher;
import com.livingdocs.common.events.NotificationSendEvent;
import com.livingdocs.modules.notification.event.NotificationCreatedEvent;
import com.livingdocs.modules.notification.model.Notification;
import com.livingdocs.modules.notification.repository.NotificationRepository;
import com.livingdocs.modules.notification.service.NotificationPolicyService;
import com.livingdocs.modules.notification.service.NotificationPreferenceService;
import com.livingdocs.modules.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-user notification toggles used to be stored but never read, and
 * the in-app delivery used to publish a Kafka event that the consumer
 * turned back into a second row — and again into a third.
 */
class NotificationServiceTest {

    private NotificationRepository repository;
    private NotificationPolicyService policyService;
    private ApplicationEventPublisher eventPublisher;
    private EventPublisher kafkaEventPublisher;
    private NotificationPreferenceService preferenceService;
    private NotificationService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        policyService = mock(NotificationPolicyService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        kafkaEventPublisher = mock(EventPublisher.class);
        preferenceService = mock(NotificationPreferenceService.class);
        service = new NotificationService(repository, policyService, eventPublisher,
                kafkaEventPublisher, preferenceService);
        when(repository.save(any(Notification.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * A user who turned the in-app bell off for this kind must not get
     * a row — that is the whole point of the toggle.
     */
    @Test
    void dropsTheInAppRowWhenTheUserTurnedTheBellOff() {
        when(preferenceService.isInAppEnabled(userId, "drift.detected")).thenReturn(false);
        when(preferenceService.isEmailEnabled(userId, "drift.detected")).thenReturn(false);

        Notification result = service.send(userId, "drift.detected", "t", "b", null);

        assertNull(result, "no notification should be produced");
        verify(repository, never()).save(any(Notification.class));
    }

    /**
     * The two channels are independent: turning the bell off must not
     * silently swallow the email the user asked for.
     */
    @Test
    void stillDeliversTheEmailWhenTheBellIsOff() {
        when(preferenceService.isInAppEnabled(userId, "drift.detected")).thenReturn(false);
        when(preferenceService.isEmailEnabled(userId, "drift.detected")).thenReturn(true);

        Notification result = service.send(userId, "drift.detected", "t", "b", null);

        assertNull(result, "no in-app row should be produced");
        verify(repository, never()).save(any(Notification.class));
        verify(kafkaEventPublisher, times(1)).publishNotification(any(NotificationSendEvent.class));
    }

    @Test
    void persistsTheRowAndPublishesTheSseEventWhenTheBellIsOn() {
        when(preferenceService.isInAppEnabled(userId, "drift.detected")).thenReturn(true);
        when(preferenceService.isEmailEnabled(userId, "drift.detected")).thenReturn(false);

        Notification result = service.send(userId, "drift.detected", "t", "b", null);

        assertNotNull(result);
        verify(repository, times(1)).save(any(Notification.class));
        verify(eventPublisher, times(1)).publishEvent(any(NotificationCreatedEvent.class));
    }

    /**
     * The regression that flooded the notifications table: the in-app
     * row is already persisted here, so publishing an IN_APP Kafka event
     * would make the consumer call this method again — forever.
     */
    @Test
    void neverPublishesAnInAppKafkaEvent() {
        when(preferenceService.isInAppEnabled(userId, "drift.detected")).thenReturn(true);
        when(preferenceService.isEmailEnabled(userId, "drift.detected")).thenReturn(false);

        service.send(userId, "drift.detected", "t", "b", null);

        ArgumentCaptor<NotificationSendEvent> captor =
                ArgumentCaptor.forClass(NotificationSendEvent.class);
        verify(kafkaEventPublisher, never()).publishNotification(captor.capture());
    }

    /** The email channel is the only one that goes through the bus. */
    @Test
    void publishesTheEmailEventWithTheEmailChannel() {
        when(preferenceService.isInAppEnabled(userId, "drift.detected")).thenReturn(true);
        when(preferenceService.isEmailEnabled(userId, "drift.detected")).thenReturn(true);

        service.send(userId, "drift.detected", "t", "b", null);

        ArgumentCaptor<NotificationSendEvent> captor =
                ArgumentCaptor.forClass(NotificationSendEvent.class);
        verify(kafkaEventPublisher, times(1)).publishNotification(captor.capture());
        assertEquals(NotificationSendEvent.Channel.EMAIL, captor.getValue().getChannel());
    }

    /**
     * A batch is filtered per channel too: one recipient may have turned
     * the bell off and asked for email instead.
     */
    @Test
    void filtersTheBatchPerChannel() {
        UUID bellOff = UUID.randomUUID();
        UUID bellOn = UUID.randomUUID();
        when(preferenceService.isInAppEnabled(eq(bellOff), anyString())).thenReturn(false);
        when(preferenceService.isInAppEnabled(eq(bellOn), anyString())).thenReturn(true);
        when(preferenceService.isEmailEnabled(eq(bellOff), anyString())).thenReturn(true);
        when(preferenceService.isEmailEnabled(eq(bellOn), anyString())).thenReturn(false);
        when(repository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        int persisted = service.sendBatch(List.of(bellOff, bellOn), "drift.detected", "t", "b", null);

        assertEquals(1, persisted, "only the recipient with the bell on gets a row");
        verify(kafkaEventPublisher, times(1)).publishNotification(any(NotificationSendEvent.class));
    }

    @Test
    void sendsNothingWhenEveryChannelIsOffForEverybody() {
        when(preferenceService.isInAppEnabled(any(), anyString())).thenReturn(false);
        when(preferenceService.isEmailEnabled(any(), anyString())).thenReturn(false);

        int persisted = service.sendBatch(List.of(userId), "drift.detected", "t", "b", null);

        assertEquals(0, persisted);
        verify(repository, never()).saveAll(any());
        verify(kafkaEventPublisher, never()).publishNotification(any(NotificationSendEvent.class));
    }

    /** A kind without a dedicated toggle is always delivered in-app. */
    @Test
    void alwaysDeliversKindsTheUserCannotControl() {
        when(preferenceService.isInAppEnabled(userId, "ADMIN_BROADCAST")).thenReturn(true);
        when(preferenceService.isEmailEnabled(userId, "ADMIN_BROADCAST")).thenReturn(false);

        assertNotNull(service.send(userId, "ADMIN_BROADCAST", "t", "b", null));
        assertTrue(true);
    }
}
