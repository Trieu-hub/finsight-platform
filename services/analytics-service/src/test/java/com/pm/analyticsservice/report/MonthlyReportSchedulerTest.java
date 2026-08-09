package com.pm.analyticsservice.report;

import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.entity.MonthlyReportSent;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.MonthlyReportSentRepository;
import com.pm.analyticsservice.service.AnalyticsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the monthly report sweep: who gets one, who is skipped because they already
 * have theirs, what the published event carries, and the ordering that keeps a crash from
 * mailing everybody twice.
 */
class MonthlyReportSchedulerTest {

    private static final YearMonth JULY = YearMonth.of(2026, 7);
    private static final String TOPIC = "finsight.reports.monthly";

    private AnalyticsService analyticsService;
    private MonthlyCategoryRollupRepository rollupRepository;
    private MonthlyReportSentRepository sentRepository;
    private KafkaTemplate<String, MonthlyReportEvent> kafkaTemplate;
    private SimpleMeterRegistry registry;
    private MonthlyReportScheduler scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        rollupRepository = mock(MonthlyCategoryRollupRepository.class);
        sentRepository = mock(MonthlyReportSentRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        registry = new SimpleMeterRegistry();
        scheduler = new MonthlyReportScheduler(analyticsService, rollupRepository, sentRepository,
                kafkaTemplate, TOPIC, registry);

        when(analyticsService.overview(anyLong(), anyInt(), anyInt(), any())).thenReturn(overview());
        when(rollupRepository.findByUserIdAndYearMonth(anyLong(), anyString()))
                .thenReturn(List.of(rollup("EXPENSE", 4L, "8000000"),
                        rollup("EXPENSE", 7L, "2000000"),
                        rollup("INCOME", 1L, "20000000")));
    }

    @Test
    void publishesOneReportPerUserWithActivity() {
        when(rollupRepository.findUserIdsWithActivityIn("2026-07")).thenReturn(List.of(1L, 2L));
        when(sentRepository.existsByUserIdAndPeriodMonth(anyLong(), anyString())).thenReturn(false);

        assertThat(scheduler.publishFor(JULY)).isEqualTo(2);
        assertThat(registry.counter(MonthlyReportScheduler.PUBLISHED_COUNTER).count()).isEqualTo(2.0);
    }

    @Test
    void skipsAUserWhoAlreadyHasThatMonthsReport() {
        // The whole point of monthly_report_sent: the sweep runs every day, and only the first
        // run of the month may result in an email.
        when(rollupRepository.findUserIdsWithActivityIn("2026-07")).thenReturn(List.of(1L, 2L));
        when(sentRepository.existsByUserIdAndPeriodMonth(1L, "2026-07")).thenReturn(true);
        when(sentRepository.existsByUserIdAndPeriodMonth(2L, "2026-07")).thenReturn(false);

        assertThat(scheduler.publishFor(JULY)).isEqualTo(1);
        assertThat(published().value().userId()).isEqualTo(2L);
    }

    @Test
    void publishesNothingForAMonthWithNoActivity() {
        when(rollupRepository.findUserIdsWithActivityIn("2026-07")).thenReturn(List.of());

        assertThat(scheduler.publishFor(JULY)).isZero();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(sentRepository, never()).save(any());
    }

    @Test
    void carriesTheFiguresAndTheLargestExpenseCategory() {
        when(rollupRepository.findUserIdsWithActivityIn("2026-07")).thenReturn(List.of(1L));
        when(sentRepository.existsByUserIdAndPeriodMonth(anyLong(), anyString())).thenReturn(false);

        scheduler.publishFor(JULY);

        ProducerRecord<String, MonthlyReportEvent> record = published();
        assertThat(record.topic()).isEqualTo(TOPIC);
        // Keyed by userId, like every other event on this platform.
        assertThat(record.key()).isEqualTo("1");
        MonthlyReportEvent event = record.value();
        assertThat(event.eventType()).isEqualTo("MonthlyReportReady");
        assertThat(event.periodMonth()).isEqualTo("2026-07");
        assertThat(event.currency()).isEqualTo("VND");
        assertThat(event.income()).isEqualByComparingTo("20000000");
        assertThat(event.expense()).isEqualByComparingTo("10000000");
        assertThat(event.savingsRate()).isEqualTo(50.0);
        // The largest expense category, not the one that moved the most — overview's top movers
        // rank by change, so the biggest category is often not among them.
        assertThat(event.topCategory()).isEqualTo("Food & Dining");
        assertThat(event.topCategoryAmount()).isEqualByComparingTo("8000000");
    }

    @Test
    void recordsTheReportBeforePublishingIt() {
        // A crash between the two costs one user one report. The other order would resend to
        // everyone, every day, until it succeeded.
        when(rollupRepository.findUserIdsWithActivityIn("2026-07")).thenReturn(List.of(1L));
        when(sentRepository.existsByUserIdAndPeriodMonth(anyLong(), anyString())).thenReturn(false);

        scheduler.publishFor(JULY);

        InOrder order = inOrder(sentRepository, kafkaTemplate);
        order.verify(sentRepository).save(any(MonthlyReportSent.class));
        order.verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, MonthlyReportEvent> published() {
        ArgumentCaptor<ProducerRecord<String, MonthlyReportEvent>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private static OverviewResponse overview() {
        return new OverviewResponse("2026-07", "VND",
                new BigDecimal("20000000"), new BigDecimal("10000000"), new BigDecimal("10000000"),
                50.0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0.0,
                null, null, List.of());
    }

    private static MonthlyCategoryRollup rollup(String type, long categoryId, String amount) {
        return MonthlyCategoryRollup.builder()
                .id(UUID.randomUUID())
                .userId(1L)
                .yearMonth("2026-07")
                .categoryId(categoryId)
                .type(type)
                .currency("VND")
                .totalAmount(new BigDecimal(amount))
                .txnCount(1)
                .build();
    }
}
