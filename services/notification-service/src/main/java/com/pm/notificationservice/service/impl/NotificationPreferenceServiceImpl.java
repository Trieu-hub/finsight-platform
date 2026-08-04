package com.pm.notificationservice.service.impl;

import com.pm.notificationservice.entity.DigestMode;
import com.pm.notificationservice.entity.Notification;
import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.repository.NotificationPreferenceRepository;
import com.pm.notificationservice.repository.NotificationRepository;
import com.pm.notificationservice.service.NotificationPreferenceService;
import com.pm.notificationservice.webhook.WebhookSigner;
import com.pm.notificationservice.webhook.WebhookUrlValidator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    /**
     * Ceiling on how many pending rows one mode change writes off in a single statement. A user
     * with more than this has bigger problems than an exact digest boundary, and an unbounded
     * {@code in (...)} on a user-triggered request is not something to leave open.
     */
    private static final int MAX_PENDING_TO_CLEAR = 500;

    private final NotificationPreferenceRepository repository;
    private final NotificationRepository notifications;
    private final WebhookUrlValidator urlValidator;

    public NotificationPreferenceServiceImpl(NotificationPreferenceRepository repository,
                                             NotificationRepository notifications,
                                             WebhookUrlValidator urlValidator) {
        this.repository = repository;
        this.notifications = notifications;
        this.urlValidator = urlValidator;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreference get(Long userId) {
        // Absent means "never chosen", which is the same as everything off. Returning a default
        // rather than an Optional keeps every caller from re-deciding what the default is.
        return repository.findById(userId).orElseGet(() -> NotificationPreference.builder()
                .userId(userId)
                .emailEnabled(false)
                .webhookEnabled(false)
                .digestMode(DigestMode.IMMEDIATE)
                .build());
    }

    @Override
    @Transactional
    public NotificationPreference setEmailEnabled(Long userId, String email, boolean enabled) {
        NotificationPreference preference = load(userId);

        preference.setEmailEnabled(enabled);
        // Refresh the address on every toggle, including when switching off: the row is the
        // record of what we were told, and a stale address is worse than no address.
        preference.setEmail(email);
        preference.setUpdatedAt(LocalDateTime.now());
        return repository.save(preference);
    }

    @Override
    @Transactional
    public String setWebhook(Long userId, String url, boolean enabled) {
        NotificationPreference preference = load(userId);

        String newSecret = null;
        if (url == null) {
            // Clearing the URL clears the secret with it, so a later re-add cannot resurrect a key
            // the user believes is gone.
            preference.setWebhookUrl(null);
            preference.setWebhookSecret(null);
            preference.setWebhookEnabled(false);
        } else {
            urlValidator.validate(url);
            if (!Objects.equals(url, preference.getWebhookUrl()) || preference.getWebhookSecret() == null) {
                newSecret = WebhookSigner.newSecret();
                preference.setWebhookSecret(newSecret);
            }
            preference.setWebhookUrl(url);
            preference.setWebhookEnabled(enabled);
        }
        preference.setUpdatedAt(LocalDateTime.now());
        repository.save(preference);
        return newSecret;
    }

    @Override
    @Transactional
    public NotificationPreference setDigestMode(Long userId, DigestMode mode) {
        NotificationPreference preference = load(userId);
        if (preference.getDigestMode() != mode) {
            clearPending(userId);
        }
        preference.setDigestMode(mode);
        preference.setUpdatedAt(LocalDateTime.now());
        return repository.save(preference);
    }

    /**
     * Writes off whatever is waiting for a digest, so the new mode starts from now. Stamped by id
     * for the same reason the scheduler does it that way — a notification arriving mid-statement
     * must not be swept up by a query that only knows "pending".
     */
    private void clearPending(Long userId) {
        List<Notification> pending = notifications.findByUserIdAndDigestedAtIsNullOrderByCreatedAtAsc(
                userId, PageRequest.of(0, MAX_PENDING_TO_CLEAR));
        if (!pending.isEmpty()) {
            notifications.markDigested(pending.stream().map(Notification::getId).toList(),
                    LocalDateTime.now());
        }
    }

    private NotificationPreference load(Long userId) {
        return repository.findById(userId)
                .orElseGet(() -> NotificationPreference.builder().userId(userId).build());
    }
}
