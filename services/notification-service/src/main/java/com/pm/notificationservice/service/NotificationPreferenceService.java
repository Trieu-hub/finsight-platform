package com.pm.notificationservice.service;

import com.pm.notificationservice.entity.DigestMode;
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

    /**
     * Points the user's webhook at {@code url} (validated first) or switches it off.
     *
     * <p>A new shared secret is minted whenever the destination changes, so a URL that is no longer
     * the user's cannot keep verifying signatures. Merely toggling the same URL off and on keeps
     * the existing secret — the receiver has it configured already.
     *
     * @return the secret if one was just minted, otherwise null. It is returned exactly once and
     *         never readable again; the caller has no second chance to show it.
     */
    String setWebhook(Long userId, String url, boolean enabled);

    /**
     * Switches batching mode.
     *
     * <p>Whatever is currently pending is written off as delivered rather than carried across: a
     * mode change starts a fresh window. Going HOURLY → IMMEDIATE would otherwise leave a partial
     * hour stuck with no scheduler to flush it, and IMMEDIATE → DAILY would sweep up rows the user
     * was already emailed about.
     */
    NotificationPreference setDigestMode(Long userId, DigestMode mode);
}
