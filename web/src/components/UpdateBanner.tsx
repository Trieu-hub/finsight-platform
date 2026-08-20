import { useEffect, useState } from 'react'
import { applyServiceWorkerUpdate, SW_UPDATE_EVENT } from '../lib/sw'
import { useI18n } from '../i18n'

/**
 * Offers the reload that hands the page over to a freshly installed service worker.
 *
 * This exists because the worker deliberately does not call `skipWaiting()` — see `sw.js`. The
 * consequence is that a deployed update sits waiting until someone asks for it, and without this
 * banner nobody ever would: the tab would keep running the old build until it was closed.
 */
export default function UpdateBanner() {
  const [ready, setReady] = useState(false)
  const { t } = useI18n()

  useEffect(() => {
    const onUpdate = () => setReady(true)
    window.addEventListener(SW_UPDATE_EVENT, onUpdate)
    return () => window.removeEventListener(SW_UPDATE_EVENT, onUpdate)
  }, [])

  if (!ready) return null

  return (
    <div
      role="status"
      className="flex items-center justify-center gap-3 border-b border-emerald-900/60 bg-emerald-950/60 px-4 py-2 text-center text-sm text-emerald-200 sm:px-5"
    >
      <span>{t('update.ready')}</span>
      <button
        type="button"
        onClick={applyServiceWorkerUpdate}
        className="rounded-md bg-emerald-500/20 px-2.5 py-1 text-xs font-medium text-emerald-100 hover:bg-emerald-500/30"
      >
        {t('update.reload')}
      </button>
    </div>
  )
}
