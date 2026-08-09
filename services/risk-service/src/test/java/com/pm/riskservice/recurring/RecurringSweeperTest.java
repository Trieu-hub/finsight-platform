package com.pm.riskservice.recurring;

import com.pm.riskservice.entity.RecurringSeries;
import com.pm.riskservice.event.RiskDetectionEmitter;
import com.pm.riskservice.repository.RecurringSeriesRepository;
import com.pm.riskservice.rule.RiskRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the sweep that reports a recurring charge which never arrived: the grace
 * period it applies, the alert it emits, and the lapse that stops it re-reporting the same
 * series every hour.
 */
class RecurringSweeperTest {

    private static final long USER = 42L;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);

    private RecurringSeriesRepository repository;
    private RiskDetectionEmitter emitter;
    private RecurringSweeper sweeper;

    @BeforeEach
    void setUp() {
        repository = mock(RecurringSeriesRepository.class);
        emitter = mock(RiskDetectionEmitter.class);
        sweeper = new RecurringSweeper(repository, emitter, RecurringSweeper.GRACE_DAYS);
    }

    @Test
    void asksOnlyForEstablishedSeriesOverdueByMoreThanTheGrace() {
        when(repository.findOverdue(anyInt(), any())).thenReturn(List.of());

        sweeper.sweep(TODAY);

        verify(repository).findOverdue(
                eq(RecurringDetector.CONFIRM_OCCURRENCES), eq(TODAY.minusDays(3)));
        verify(emitter, never()).emit(any(), any(), any());
    }

    @Test
    void reportsAnOverdueChargeAgainstItsLastTransaction() {
        UUID lastTransaction = UUID.randomUUID();
        when(repository.findOverdue(anyInt(), any()))
                .thenReturn(List.of(series(lastTransaction)));

        sweeper.sweep(TODAY);

        // There is no triggering transaction for an absence, so the alert points at the charge
        // that was supposed to repeat.
        verify(emitter).emit(USER, lastTransaction, RiskRule.RECURRING_CHARGE_MISSED);
    }

    @Test
    void lapsesTheSeriesSoItIsReportedOnlyOnce() {
        when(repository.findOverdue(anyInt(), any()))
                .thenReturn(List.of(series(UUID.randomUUID())));

        sweeper.sweep(TODAY);

        ArgumentCaptor<RecurringSeries> captor = ArgumentCaptor.forClass(RecurringSeries.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(RecurringSeries.LAPSED);
        assertThat(captor.getValue().isActive()).isFalse();
    }

    private static RecurringSeries series(UUID lastTransactionId) {
        return new RecurringSeries(
                UUID.randomUUID(), USER, 4L, "USD", new BigDecimal("9.99"), 30,
                TODAY.minusDays(70), TODAY.minusDays(40), lastTransactionId,
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
