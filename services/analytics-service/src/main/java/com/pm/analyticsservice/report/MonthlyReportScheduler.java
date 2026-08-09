package com.pm.analyticsservice.report;

import com.pm.analyticsservice.catalog.CategoryCatalog;
import com.pm.analyticsservice.dto.OverviewResponse;
import com.pm.analyticsservice.entity.MonthlyCategoryRollup;
import com.pm.analyticsservice.entity.MonthlyReportSent;
import com.pm.analyticsservice.repository.MonthlyCategoryRollupRepository;
import com.pm.analyticsservice.repository.MonthlyReportSentRepository;
import com.pm.analyticsservice.service.AnalyticsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Publishes each user's monthly report once the month is over (Phase G.2).
 *
 * <p>This is the first thing analytics-service <em>produces</em>. It exists because the figures
 * live here and nowhere else: notification-service owns delivery (in-app, push, email, webhook)
 * but has no access to {@code analytics_db} and must not call this service at runtime, so the
 * event carries the finished numbers rather than a reference to them.
 *
 * <h4>Why a sweep and not an event</h4>
 * <p>"The month ended" is not something any service publishes. The sweep runs daily and always
 * targets the <em>previous</em> calendar month; {@code monthly_report_sent} makes every run after
 * the first a no-op. That also makes it self-healing: if the box was down on the 1st, the next
 * day's run sends what was missed instead of losing the month.
 *
 * <p><b>Single instance</b>, like notification-service's digest scheduler and transaction-service's
 * outbox relay. Two would race for the same users; the unique constraint on
 * {@code monthly_report_sent} would stop the duplicate row, but only after both had published.
 *
 * <p>Gated on {@code finsight.kafka.enabled} (with no broker there is nowhere to publish, and the
 * test profile must not try) <b>and</b> on {@code finsight.report.monthly.enabled}, which is
 * <b>off by default</b> — the same posture as the delivery channels in notification-service. The
 * first run against a populated database mails every user who was active last month, so turning
 * that on is a decision someone makes deliberately, not something a deploy does at 03:20.
 */
@Component
@ConditionalOnProperty(
        name = {"finsight.kafka.enabled", "finsight.report.monthly.enabled"},
        havingValue = "true")
public class MonthlyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportScheduler.class);

    static final String PUBLISHED_COUNTER = "finsight.analytics.reports.published";

    private static final String EXPENSE = "EXPENSE";

    private final AnalyticsService analyticsService;
    private final MonthlyCategoryRollupRepository rollupRepository;
    private final MonthlyReportSentRepository sentRepository;
    private final KafkaTemplate<String, MonthlyReportEvent> kafkaTemplate;
    private final String topic;
    private final Counter published;

    public MonthlyReportScheduler(AnalyticsService analyticsService,
                                  MonthlyCategoryRollupRepository rollupRepository,
                                  MonthlyReportSentRepository sentRepository,
                                  KafkaTemplate<String, MonthlyReportEvent> kafkaTemplate,
                                  @Value("${finsight.kafka.topics.monthly-report}") String topic,
                                  MeterRegistry meterRegistry) {
        this.analyticsService = analyticsService;
        this.rollupRepository = rollupRepository;
        this.sentRepository = sentRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.published = Counter.builder(PUBLISHED_COUNTER)
                .description("Monthly reports published to the reports topic")
                .register(meterRegistry);
    }

    /** Daily, early enough that a report for the 1st arrives before the user's day starts. */
    @Scheduled(cron = "${finsight.report.monthly.cron:0 20 3 * * *}")
    public void publishDueReports() {
        publishFor(YearMonth.from(LocalDate.now()).minusMonths(1));
    }

    /**
     * Publishes the report for one month to every user who had activity in it and has not been
     * sent it. Package-private so tests can pick the month instead of waiting for one to pass.
     *
     * @return how many reports were published
     */
    int publishFor(YearMonth month) {
        String periodMonth = month.toString();
        List<Long> userIds = rollupRepository.findUserIdsWithActivityIn(periodMonth);
        int sent = 0;
        for (Long userId : userIds) {
            if (sentRepository.existsByUserIdAndPeriodMonth(userId, periodMonth)) {
                continue;
            }
            publishOne(userId, month, periodMonth);
            sent++;
        }
        if (sent > 0) {
            log.info("Published {} monthly reports for {}", sent, periodMonth);
        }
        return sent;
    }

    /**
     * Records the report as sent <em>before</em> publishing, for the same reason the digest
     * scheduler stamps before delivering: a crash between the two costs one user one report,
     * where the other order would resend it to everyone every day until it succeeded.
     */
    private void publishOne(Long userId, YearMonth month, String periodMonth) {
        OverviewResponse overview =
                analyticsService.overview(userId, month.getYear(), month.getMonthValue(), null);
        MonthlyCategoryRollup top = topExpense(userId, periodMonth, overview.currency());

        sentRepository.save(MonthlyReportSent.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .periodMonth(periodMonth)
                .sentAt(LocalDateTime.now())
                .build());

        MonthlyReportEvent event = MonthlyReportEvent.of(
                userId, periodMonth, overview.currency(),
                overview.income(), overview.expense(), overview.net(), overview.savingsRate(),
                top == null ? null : CategoryCatalog.name(top.getCategoryId()),
                top == null ? null : top.getTotalAmount());
        kafkaTemplate.send(new ProducerRecord<>(topic, String.valueOf(userId), event));
        published.increment();
    }

    /**
     * The month's largest expense category in the report's currency — the one figure a report
     * needs that {@code overview} does not carry (its "top movers" rank by change, not by size,
     * so the biggest category is often not among them).
     */
    private MonthlyCategoryRollup topExpense(Long userId, String periodMonth, String currency) {
        return rollupRepository.findByUserIdAndYearMonth(userId, periodMonth).stream()
                .filter(row -> EXPENSE.equals(row.getType()) && row.getCurrency().equals(currency))
                .max(Comparator.comparing(MonthlyCategoryRollup::getTotalAmount,
                        Comparator.nullsFirst(BigDecimal::compareTo)))
                .orElse(null);
    }
}
