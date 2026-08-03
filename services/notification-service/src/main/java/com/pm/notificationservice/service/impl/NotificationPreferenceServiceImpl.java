package com.pm.notificationservice.service.impl;

import com.pm.notificationservice.entity.NotificationPreference;
import com.pm.notificationservice.repository.NotificationPreferenceRepository;
import com.pm.notificationservice.service.NotificationPreferenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;

    public NotificationPreferenceServiceImpl(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationPreference get(Long userId) {
        // Absent means "never chosen", which is the same as everything off. Returning a default
        // rather than an Optional keeps every caller from re-deciding what the default is.
        return repository.findById(userId).orElseGet(() -> NotificationPreference.builder()
                .userId(userId)
                .emailEnabled(false)
                .build());
    }

    @Override
    @Transactional
    public NotificationPreference setEmailEnabled(Long userId, String email, boolean enabled) {
        NotificationPreference preference = repository.findById(userId)
                .orElseGet(() -> NotificationPreference.builder().userId(userId).build());

        preference.setEmailEnabled(enabled);
        // Refresh the address on every toggle, including when switching off: the row is the
        // record of what we were told, and a stale address is worse than no address.
        preference.setEmail(email);
        preference.setUpdatedAt(LocalDateTime.now());
        return repository.save(preference);
    }
}
