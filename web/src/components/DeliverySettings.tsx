import { useEffect, useState } from 'react'
import { notificationPreferences, setEmailAlerts } from '../api/endpoints'
import { useWebPush } from '../hooks/useWebPush'
import { useI18n } from '../i18n'

/**
 * The "where do my alerts go" rows inside the notification bell.
 *
 * Each channel renders only when it can actually deliver: no VAPID keypair on the server, or a
 * browser without the Push API, and the row is absent rather than present-and-broken. Offering a
 * switch that silently does nothing is worse than offering none.
 */
export default function DeliverySettings() {
  const { t } = useI18n()
  const { state, busy, enable, disable } = useWebPush()

  const [emailOn, setEmailOn] = useState(false)
  const [emailConfigured, setEmailConfigured] = useState(false)
  const [emailBusy, setEmailBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    notificationPreferences()
      .then((prefs) => {
        if (cancelled) return
        setEmailOn(prefs.emailEnabled)
        setEmailConfigured(prefs.emailConfigured)
      })
      // A failed probe just leaves the row hidden; the bell itself must keep working.
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [])

  async function toggleEmail() {
    setEmailBusy(true)
    try {
      const prefs = await setEmailAlerts(!emailOn)
      setEmailOn(prefs.emailEnabled)
    } catch {
      // leave the switch where it was; the next open re-reads the truth
    } finally {
      setEmailBusy(false)
    }
  }

  const pushHidden = state === 'unsupported' || state === 'unconfigured'
  if (pushHidden && !emailConfigured) return null

  return (
    <div className="space-y-2 border-b border-neutral-800 px-4 py-2.5">
      {emailConfigured && (
        <div className="flex items-center justify-between gap-3">
          <span className="text-xs text-neutral-400">{t('notif.email')}</span>
          <button
            onClick={toggleEmail}
            disabled={emailBusy}
            // Both rows read "Turn on" visually, so the channel has to come from the accessible
            // name or the two buttons are indistinguishable; aria-pressed carries the state.
            aria-label={t('notif.email')}
            aria-pressed={emailOn}
            className={`rounded-lg border px-2 py-1 text-xs font-medium transition disabled:opacity-50 ${
              emailOn
                ? 'border-emerald-600/40 bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20'
                : 'border-neutral-700 text-neutral-300 hover:bg-neutral-800'
            }`}
          >
            {emailOn ? t('notif.emailOn') : t('notif.emailOff')}
          </button>
        </div>
      )}

      {!pushHidden && (
        <div className="flex items-center justify-between gap-3">
          <span className="text-xs text-neutral-400">{t('notif.push')}</span>
          {state === 'denied' ? (
            // Once the browser has recorded a denial the page cannot ask again; only the user can
            // undo it in site settings. Saying so beats a button that would do nothing.
            <span className="text-xs text-neutral-500">{t('notif.pushDenied')}</span>
          ) : (
            <button
              onClick={state === 'on' ? disable : enable}
              disabled={busy}
              aria-label={t('notif.push')}
              aria-pressed={state === 'on'}
              className={`rounded-lg border px-2 py-1 text-xs font-medium transition disabled:opacity-50 ${
                state === 'on'
                  ? 'border-emerald-600/40 bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20'
                  : 'border-neutral-700 text-neutral-300 hover:bg-neutral-800'
              }`}
            >
              {state === 'on' ? t('notif.pushOn') : t('notif.pushOff')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
