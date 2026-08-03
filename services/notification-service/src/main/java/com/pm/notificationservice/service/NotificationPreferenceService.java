package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.NotificationPreference;

public interface NotificationPreferenceService {

    /** Current preferences, defaulted (everything off) for a user who has never set any. */
    NotificationPreference get(Long userId);

    /**
     * Turns email alerts on or off.
     *
     * @param email the address from the caller's JWT — the only source, exactly as {@code userId}
     *              is. Stored when enabling so a later alert has somewhere to go, and refreshed on
     *              every toggle so a changed address eventually catches up.
     */
    NotificationPreference setEmailEnabled(Long userId, String email, boolean enabled);
}
