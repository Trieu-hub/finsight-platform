package com.pm.notificationservice.service.impl;

import com.pm.notificationservice.delivery.DeliveryChannel;
import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.ProcessedEvent;
import com.pm.notificationservice.event.BudgetExceededEvent;
import com.pm.notificationservice.event.MonthlyReportEvent;
import com.pm.notificationservice.event.RiskDetectedEvent;
import com.pm.notificationservice.exception.NotificationNotFoundException;
import com.pm.notificationservice.narrator.AlertContent;
import com.pm.notificationservice.narrator.AlertNarrator;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.repository.ProcessedEventRepository;
import com.pm.notificationservice.service.NotificationPreferenceService;
import com.pm.notificationservice.service.NotificationService;
import com.pm.notificationservice.stream.NotificationStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    static final String BUDGET_EXCEEDED_TYPE = "BUDGET_EXCEEDED";
    /** Over budget is a certainty, not a suspicion — it ranks with the high-amount risk alerts. */
    static final String BUDGET_EXCEEDED_SEVERITY = "HIGH";

    static final String MONTHLY_REPORT_TYPE = "MONTHLY_REPORT";
    /** Nothing is wrong in a summary; it must not colour the bell like an alert. */
    static final String MONTHLY_REPORT_SEVERITY = "LOW";

    /**
     * Fixed US symbols so the grouping separator does not follow the server's default locale —
     * the same amount must read identically wherever this runs.
     */
    private static final DecimalFormat MONEY =
            new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private final NotificationRepository notificationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AlertNarrator narrator;
    private final NotificationStream notificationStream;
    private final List<DeliveryChannel> deliveryChannels;
    private final NotificationPreferenceService preferences;
    private final TransactionTemplate transactionTemplate;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   ProcessedEventRepository processedEventRepository,
                                   AlertNarrator narrator,
                                   NotificationStream notificationStream,
                                   List<DeliveryChannel> deliveryChannels,
                                   NotificationPreferenceService preferences,
                                   PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.processedEventRepository = processedEventRepository;
        this.narrator = narrator;
        this.notificationStream = notificationStream;
        this.deliveryChannels = deliveryChannels;
        this.preferences = preferences;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotent create. Narration runs FIRST and OUTSIDE the database transaction: the narrator
     * may call an external LLM, and holding a DB connection open across a network call would tie
     * up the pool. The duplicate check also short-circuits before narration, so a redelivered
     * event never pays for a second LLM call. Only the two inserts run transactionally — with a
     * second inbox check inside the transaction (and the {@code processed_events} PK as the final
     * backstop) guarding against a concurrent double-insert.
     */
    @Override
    public boolean createFromEvent(RiskDetectedEvent event) {
        // Narration runs before the transaction: the optional LLM narrator is a network call, and
        // holding a DB connection open across it would be the wrong trade.
        return create(event.eventId(), event.userId(), event.riskSeverity(), narrator.narrate(event));
    }

    @Override
    public boolean createFromBudgetExceeded(BudgetExceededEvent event) {
        return create(event.eventId(), event.userId(), BUDGET_EXCEEDED_SEVERITY, budgetContent(event));
    }

    /**
     * Wording for an exceeded budget, written here rather than behind {@code AlertNarrator}: that
     * port is shaped around {@code RiskDetected} (its optional LLM implementation is prompted with
     * a risk type and severity), and reshaping it to carry a second, unrelated event type would
     * cost more than the two lines it would save.
     *
     * <p>The figures come from budget-service, which owns {@code spent_amount} — so the message
     * agrees with what the Budgets page shows rather than with a second, independently derived
     * number.
     */
    private AlertContent budgetContent(BudgetExceededEvent event) {
        String currency = event.currency() == null ? "" : " " + event.currency();
        String spent = MONEY.format(event.spentAmount());
        String limit = MONEY.format(event.limitAmount());
        String message = "You have spent " + spent + currency + " against a " + limit + currency
                + " budget — " + MONEY.format(overBy(event)) + currency + " over the limit.";
        return new AlertContent(BUDGET_EXCEEDED_TYPE, "Budget exceeded", message);
    }

    private static BigDecimal overBy(BudgetExceededEvent event) {
        return event.spentAmount().subtract(event.limitAmount());
    }

    @Override
    public boolean createFromMonthlyReport(MonthlyReportEvent event) {
        return create(event.eventId(), event.userId(), MONTHLY_REPORT_SEVERITY, reportContent(event));
    }

    /**
     * A month in review, written here for the same reason the budget wording is: {@code
     * AlertNarrator} is shaped around a risk type and severity, and a report is neither.
     *
     * <p>The figures arrive on the event — analytics-service owns them, and this service has no
     * access to {@code analytics_db} — so the numbers in the email are the same ones the
     * Analytics page shows rather than a second, independently derived set.
     */
    private AlertContent reportContent(MonthlyReportEvent event) {
        String currency = event.currency() == null ? "" : " " + event.currency();
        StringBuilder message = new StringBuilder()
                .append("In ").append(event.periodMonth()).append(" you took in ")
                .append(MONEY.format(event.income())).append(currency).append(" and spent ")
                .append(MONEY.format(event.expense())).append(currency).append(", leaving ")
                .append(MONEY.format(event.net())).append(currency)
                .append(" (").append(MONEY.format(event.savingsRate())).append("% of income kept).");
        if (event.topCategory() != null) {
            message.append(" Your largest category was ").append(event.topCategory())
                    .append(" at ").append(MONEY.format(event.topCategoryAmount()))
                    .append(currency).append('.');
        }
        return new AlertContent(MONTHLY_REPORT_TYPE, "Your " + event.periodMonth() + " summary",
                message.toString());
    }

    /**
     * The shared tail of both entry points: inbox dedup, then one transaction holding the
     * notification and its inbox row, then the after-commit fan-out.
     */
    private boolean create(UUID eventId, Long userId, String severity, AlertContent content) {
        if (processedEventRepository.existsById(eventId)) {
            return false;
        }

        // Read outside the transaction, like the narration above: it decides only how this alert
        // is delivered, and a stale read costs at most one alert landing on the previous schedule.
        DigestMode digestMode = preferences.get(userId).getDigestMode();

        Notification created = transactionTemplate.execute(status -> {
            if (processedEventRepository.existsById(eventId)) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now();

            Notification notification = Notification.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .type(content.type())
                    .severity(severity)
                    .title(content.title())
                    .message(content.message())
                    .sourceEventId(eventId)
                    .read(false)
                    .createdAt(now)
                    // Stamped now for an immediate user, so a null digestedAt always means "still
                    // owed a delivery" rather than "created before digests existed".
                    .digestedAt(digestMode.isDeferred() ? null : now)
                    .build();
            notificationRepository.save(notification);

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .processedAt(now)
                    .build());
            return notification;
        });

        if (created == null) {
            return false;
        }
        // Push AFTER the commit: a rolled-back insert must never surface in a user's bell.
        // Best-effort — the row is already durable, so a failed push only costs the client its
        // fallback poll interval.
        notificationStream.publish(created);

        deliver(userId, List.of(created), digestMode.isDeferred());
        return true;
    }

    /**
     * Hands a batch to the delivery channels, best-effort.
     *
     * <p>A channel that threw must not propagate. Failing here would fail the Kafka listener, the
     * event would be redelivered, and every user who *did* get their alert would get it again.
     *
     * @param deferred when true the channels that carry the alert text are skipped — the digest
     *                 scheduler owns them for this user. The payload-free push still goes now.
     */
    void deliver(Long userId, List<Notification> batch, boolean deferred) {
        for (DeliveryChannel channel : deliveryChannels) {
            if (deferred && channel.respectsDigest()) {
                continue;
            }
            try {
                channel.deliver(userId, batch);
            } catch (RuntimeException e) {
                log.warn("Delivery channel {} failed for user {}: {}",
                        channel.getClass().getSimpleName(), userId, e.toString());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, boolean unreadOnly, Pageable pageable) {
        return unreadOnly
                ? notificationRepository.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public Notification markRead(Long userId, UUID id) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotificationNotFoundException(
                        "Notification " + id + " was not found"));
        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(LocalDateTime.now());
        }
        return notification;
    }

    @Override
    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now());
    }
}
