package com.pm.notificationservice.delivery;

import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.service.NotificationPreferenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigestSchedulerTest {

    private NotificationRepository notifications;
    private NotificationPreferenceService preferences;
    private DeliveryChannel batchedChannel;
    private DeliveryChannel immediateChannel;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        preferences = mock(NotificationPreferenceService.class);
        batchedChannel = mock(DeliveryChannel.class);
        when(batchedChannel.respectsDigest()).thenReturn(true);
        immediateChannel = mock(DeliveryChannel.class);
        when(immediateChannel.respectsDigest()).thenReturn(false);
    }

    @Test
    void holdsTheDigestBackUntilTheWindowHasElapsed() {
        givenPending(7L, DigestMode.HOURLY, minutesAgo(20));

        scheduler().flushDueDigests();

        verify(batchedChannel, never()).deliver(any(), any());
        verify(notifications, never()).markDigested(anyCollection(), any());
    }

    @Test
    void sendsOneDeliveryCoveringTheWholeWindow() {
        // Three alerts in the hour become one email, which is the entire point of the feature.
        givenPending(7L, DigestMode.HOURLY, minutesAgo(70), minutesAgo(40), minutesAgo(5));

        scheduler().flushDueDigests();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Notification>> batch = ArgumentCaptor.forClass(List.class);
        verify(batchedChannel).deliver(eq(7L), batch.capture());
        assertThat(batch.getValue()).hasSize(3);
    }

    @Test
    void theWindowIsTheOneTheUserChose() {
        // The same 70-minute-old alert that is due on HOURLY is not due on DAILY. Asserting both
        // modes against one age is what proves the window is read from the preference rather than
        // hardcoded.
        givenPending(7L, DigestMode.DAILY, minutesAgo(70));

        scheduler().flushDueDigests();

        verify(batchedChannel, never()).deliver(any(), any());
    }

    @Test
    void leavesTheImmediateChannelsToTheCreatePath() {
        // Web push already fired when the alert arrived; pushing again here would double-buzz.
        givenPending(7L, DigestMode.HOURLY, minutesAgo(70));

        scheduler().flushDueDigests();

        verify(immediateChannel, never()).deliver(any(), any());
    }

    @Test
    void stampsExactlyTheRowsItSent() {
        Notification pending = notification(minutesAgo(70));
        when(notifications.findUserIdsWithPendingDigest()).thenReturn(List.of(7L));
        when(preferences.get(7L)).thenReturn(preference(DigestMode.HOURLY));
        when(notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(pending));

        scheduler().flushDueDigests();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(notifications).markDigested(ids.capture(), any());
        assertThat(ids.getValue()).containsExactly(pending.getId());
    }

    @Test
    void stampsButDoesNotResendStragglersLeftByAUserOnImmediate() {
        // These were already delivered as they arrived. Sending them would duplicate; leaving them
        // pending would grow the scheduler's working set forever.
        givenPending(7L, DigestMode.IMMEDIATE, minutesAgo(70));

        scheduler().flushDueDigests();

        verify(batchedChannel, never()).deliver(any(), any());
        verify(notifications).markDigested(anyCollection(), any());
    }

    @Test
    void oneBrokenUserDoesNotStopEveryoneElse() {
        when(notifications.findUserIdsWithPendingDigest()).thenReturn(List.of(1L, 2L));
        when(preferences.get(1L)).thenThrow(new IllegalStateException("boom"));
        when(preferences.get(2L)).thenReturn(preference(DigestMode.HOURLY));
        when(notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(eq(2L), any(Pageable.class)))
                .thenReturn(List.of(notification(minutesAgo(70))));

        assertThatCode(() -> scheduler().flushDueDigests()).doesNotThrowAnyException();

        verify(batchedChannel).deliver(eq(2L), any());
    }

    @Test
    void aChannelThatThrowsDoesNotStrandTheStamp() {
        // The rows are stamped before delivery on purpose: a receiver that is down must not turn
        // into the same digest being resent every poll, forever.
        givenPending(7L, DigestMode.HOURLY, minutesAgo(70));
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(batchedChannel).deliver(any(), any());

        assertThatCode(() -> scheduler().flushDueDigests()).doesNotThrowAnyException();

        verify(notifications).markDigested(anyCollection(), any());
    }

    private DigestScheduler scheduler() {
        return new DigestScheduler(notifications, preferences,
                List.of(batchedChannel, immediateChannel), new SimpleMeterRegistry());
    }

    private void givenPending(Long userId, DigestMode mode, LocalDateTime... createdAt) {
        when(notifications.findUserIdsWithPendingDigest()).thenReturn(List.of(userId));
        when(preferences.get(userId)).thenReturn(preference(mode));
        when(notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(eq(userId), any(Pageable.class)))
                .thenReturn(java.util.Arrays.stream(createdAt)
                        .map(DigestSchedulerTest::notification)
                        .toList());
    }

    private static LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }

    private static NotificationPreference preference(DigestMode mode) {
        return NotificationPreference.builder().userId(7L).digestMode(mode).build();
    }

    private static Notification notification(LocalDateTime createdAt) {
        return Notification.builder()
                .id(UUID.randomUUID())
                .userId(7L)
                .type("RISK_ALERT")
                .severity("HIGH")
                .title("Large expense")
                .message("You spent a lot.")
                .read(false)
                .createdAt(createdAt)
                .build();
    }
}
