import { useEffect, useState } from 'react'
import {
  notificationPreferences,
  setDigestMode,
  setEmailAlerts,
  setWebhook,
  type DigestMode,
  type NotificationPreferences,
} from '../api/endpoints'
import { useWebPush } from '../hooks/useWebPush'
import { useI18n } from '../i18n'

const DIGEST_MODES: DigestMode[] = ['IMMEDIATE', 'HOURLY', 'DAILY']

/**
 * The "where do my alerts go" rows inside the notification bell.
 *
 * Each channel renders only when it can actually deliver: no VAPID keypair on the server, or a
 * browser without the Push API, and the row is absent rather than present-and-broken. Offering a
 * switch that silently does nothing is worse than offering none.
 *
 * The webhook and digest rows have no such condition — they need nothing configured server-side,
 * so they are always available.
 */
export default function DeliverySettings() {
  const { t } = useI18n()
  const { state, busy, enable, disable } = useWebPush()

  const [emailOn, setEmailOn] = useState(false)
  const [emailConfigured, setEmailConfigured] = useState(false)
  const [emailBusy, setEmailBusy] = useState(false)

  const [digest, setDigest] = useState<DigestMode>('IMMEDIATE')
  const [digestBusy, setDigestBusy] = useState(false)

  const [webhookUrl, setWebhookUrl] = useState('')
  const [webhookSaved, setWebhookSaved] = useState<string | null>(null)
  const [webhookBusy, setWebhookBusy] = useState(false)
  const [webhookError, setWebhookError] = useState<string | null>(null)
  // Held in component state and never re-fetched: the server returns it once, on the response that
  // minted it. If this is lost the user has to re-save the URL to get a new one.
  const [freshSecret, setFreshSecret] = useState<string | null>(null)

  function absorb(prefs: NotificationPreferences) {
    setEmailOn(prefs.emailEnabled)
    setEmailConfigured(prefs.emailConfigured)
    setDigest(prefs.digestMode)
    setWebhookUrl(prefs.webhookUrl ?? '')
    setWebhookSaved(prefs.webhookUrl)
    if (prefs.webhookSecret) setFreshSecret(prefs.webhookSecret)
  }

  useEffect(() => {
    let cancelled = false
    notificationPreferences()
      .then((prefs) => {
        if (cancelled) return
        absorb(prefs)
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

  async function chooseDigest(mode: DigestMode) {
    setDigestBusy(true)
    try {
      const prefs = await setDigestMode(mode)
      setDigest(prefs.digestMode)
    } catch {
      // same as above: the stored value is the truth, and it is unchanged
    } finally {
      setDigestBusy(false)
    }
  }

  async function saveWebhook(url: string | null) {
    setWebhookBusy(true)
    setWebhookError(null)
    try {
      const prefs = await setWebhook(url, url !== null)
      absorb(prefs)
      if (url === null) setFreshSecret(null)
    } catch (e) {
      // The server's message is the useful one here ("must use https", "must point at a public
      // address") — it is the only place the SSRF rules are visible to whoever is configuring this.
      const detail = (e as { response?: { data?: { error?: { message?: string } } } })?.response
        ?.data?.error?.message
      setWebhookError(detail ?? 'Could not save')
    } finally {
      setWebhookBusy(false)
    }
  }

  // 'error' hides the row the same way 'unsupported' does — we never found out whether push works
  // here, so offering the switch would be a guess — but the hook keeps the two apart, and logs
  // which one it was.
  const pushHidden = state === 'unsupported' || state === 'unconfigured' || state === 'error'
  const trimmedUrl = webhookUrl.trim()

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

      <div className="space-y-1">
        <span className="text-xs text-neutral-400">{t('notif.webhook')}</span>
        <div className="flex items-center gap-2">
          <input
            type="url"
            value={webhookUrl}
            onChange={(e) => setWebhookUrl(e.target.value)}
            placeholder={t('notif.webhookPlaceholder')}
            aria-label={t('notif.webhook')}
            className="min-w-0 flex-1 rounded-lg border border-neutral-700 bg-neutral-900 px-2 py-1 text-xs text-neutral-200 placeholder:text-neutral-600"
          />
          <button
            onClick={() => saveWebhook(trimmedUrl)}
            disabled={webhookBusy || trimmedUrl === '' || trimmedUrl === webhookSaved}
            className="rounded-lg border border-neutral-700 px-2 py-1 text-xs font-medium text-neutral-300 transition hover:bg-neutral-800 disabled:opacity-50"
          >
            {t('notif.webhookSave')}
          </button>
          {webhookSaved && (
            <button
              onClick={() => saveWebhook(null)}
              disabled={webhookBusy}
              className="rounded-lg border border-neutral-700 px-2 py-1 text-xs font-medium text-neutral-400 transition hover:bg-neutral-800 disabled:opacity-50"
            >
              {t('notif.webhookClear')}
            </button>
          )}
        </div>
        {webhookError && (
          <p role="alert" className="text-xs text-red-400">
            {webhookError}
          </p>
        )}
        {freshSecret && (
          <p className="break-all text-xs text-amber-400">
            {t('notif.webhookSecret')} <code className="font-mono">{freshSecret}</code>
          </p>
        )}
      </div>

      <div className="space-y-1">
        <span className="text-xs text-neutral-400">{t('notif.digest')}</span>
        <div className="flex gap-1">
          {DIGEST_MODES.map((mode) => (
            <button
              key={mode}
              onClick={() => chooseDigest(mode)}
              disabled={digestBusy}
              aria-pressed={digest === mode}
              className={`rounded-lg border px-2 py-1 text-xs font-medium transition disabled:opacity-50 ${
                digest === mode
                  ? 'border-emerald-600/40 bg-emerald-500/10 text-emerald-400'
                  : 'border-neutral-700 text-neutral-300 hover:bg-neutral-800'
              }`}
            >
              {t(`notif.digest${mode}`)}
            </button>
          ))}
        </div>
        <p className="text-xs text-neutral-600">{t('notif.digestHint')}</p>
      </div>
    </div>
  )
}
