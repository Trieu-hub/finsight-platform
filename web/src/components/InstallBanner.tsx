import { useInstallPrompt } from '../hooks/useInstallPrompt'
import { useI18n } from '../i18n'

/**
 * Tells people the app can be installed, which the browser otherwise barely does.
 *
 * Chromium's own prompt is a thin bar that is easy to miss and cannot be recalled; Safari shows
 * nothing at all. So the app was installable for months while nobody was told — this closes that
 * gap, and nothing more: it never nags twice (the dismissal is remembered) and it disappears
 * entirely once the app is running from the home screen.
 */
export default function InstallBanner() {
  const { offer, install, dismiss } = useInstallPrompt()
  const { t } = useI18n()

  if (offer === 'none') return null

  return (
    <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1.5 border-b border-neutral-800 bg-neutral-900 px-4 py-2 text-center text-sm text-neutral-300 sm:px-5">
      <span>{offer === 'ios' ? t('install.ios') : t('install.body')}</span>
      <span className="flex items-center gap-2">
        {/* Safari has no install API, so there is no button to offer — only the instructions. */}
        {offer === 'prompt' && (
          <button
            type="button"
            onClick={install}
            className="rounded-md bg-emerald-500/20 px-2.5 py-1 text-xs font-medium text-emerald-200 hover:bg-emerald-500/30"
          >
            {t('install.cta')}
          </button>
        )}
        <button
          type="button"
          onClick={dismiss}
          className="rounded-md px-2.5 py-1 text-xs font-medium text-neutral-400 hover:text-neutral-200"
        >
          {t('install.dismiss')}
        </button>
      </span>
    </div>
  )
}
