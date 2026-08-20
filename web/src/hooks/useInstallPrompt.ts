import { useCallback, useEffect, useState } from 'react'

/**
 * Whether, and how, this browser can be offered an install.
 *
 * - `none` — nothing to offer: already installed, dismissed, or a browser that cannot install.
 * - `prompt` — Chromium fired `beforeinstallprompt`, so there is a real dialog to open.
 * - `ios` — Safari never fires that event and offers no API at all; the only way in is the user
 *   tapping Share → Add to Home Screen, so all we can do is say so.
 */
export type InstallOffer = 'none' | 'prompt' | 'ios'

/** Chromium-only, and not in lib.dom — declared here rather than widened globally. */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

const DISMISSED_KEY = 'vernfy.install.dismissed'

/** True once the app is running from the home screen, where offering an install is nonsense. */
function isInstalled(): boolean {
  if (typeof window === 'undefined') return false
  // `navigator.standalone` is the iOS-only spelling; matchMedia is the standard one, and is
  // missing in some test environments, hence the guard rather than a direct call.
  const iosStandalone = (window.navigator as { standalone?: boolean }).standalone === true
  const displayMode = window.matchMedia?.('(display-mode: standalone)').matches === true
  return iosStandalone || displayMode
}

function isIos(): boolean {
  if (typeof navigator === 'undefined') return false
  // iPadOS 13+ reports a Mac user-agent, so it is caught by the touch check rather than the name.
  const byName = /iphone|ipad|ipod/i.test(navigator.userAgent)
  const iPadOsAsMac = /macintosh/i.test(navigator.userAgent) && navigator.maxTouchPoints > 1
  return byName || iPadOsAsMac
}

function wasDismissed(): boolean {
  try {
    return window.localStorage.getItem(DISMISSED_KEY) === '1'
  } catch {
    // Storage can be blocked outright (private mode, cookie policy). Treat that as "not dismissed"
    // rather than letting an exception take the banner — and the whole layout — down with it.
    return false
  }
}

/**
 * Drives the install banner.
 *
 * The interesting part is that Chromium only fires `beforeinstallprompt` when it considers the
 * app installable *and* the user engaged enough — so the event may arrive seconds after mount, or
 * never. The hook therefore starts at `none` and upgrades itself when the event lands, instead of
 * asking a question at mount time that has no answer yet.
 */
export function useInstallPrompt() {
  const [deferred, setDeferred] = useState<BeforeInstallPromptEvent | null>(null)
  const [dismissed, setDismissed] = useState(() => wasDismissed())
  const [installed, setInstalled] = useState(() => isInstalled())

  useEffect(() => {
    const onBeforeInstall = (event: Event) => {
      // Without preventDefault Chromium shows its own mini-infobar, which is easy to miss and
      // cannot be re-opened. Suppressing it is what buys the right to ask at a better moment.
      event.preventDefault()
      setDeferred(event as BeforeInstallPromptEvent)
    }
    const onInstalled = () => {
      setInstalled(true)
      setDeferred(null)
    }
    window.addEventListener('beforeinstallprompt', onBeforeInstall)
    window.addEventListener('appinstalled', onInstalled)
    return () => {
      window.removeEventListener('beforeinstallprompt', onBeforeInstall)
      window.removeEventListener('appinstalled', onInstalled)
    }
  }, [])

  const dismiss = useCallback(() => {
    setDismissed(true)
    try {
      window.localStorage.setItem(DISMISSED_KEY, '1')
    } catch {
      // Remembering the dismissal is a courtesy; failing to store it must not throw at the user.
    }
  }, [])

  const install = useCallback(async () => {
    if (!deferred) return
    await deferred.prompt()
    await deferred.userChoice
    // The event is single-use: Chromium refuses a second `prompt()` on the same one. Clearing it
    // hides the banner either way — accepted means installed, declined means asked and answered.
    setDeferred(null)
  }, [deferred])

  let offer: InstallOffer = 'none'
  if (!installed && !dismissed) {
    if (deferred) offer = 'prompt'
    else if (isIos()) offer = 'ios'
  }

  return { offer, install, dismiss }
}
