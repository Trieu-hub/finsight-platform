import { useCallback, useEffect, useState } from 'react'
import { pushConfig, subscribeToPush, unsubscribeFromPush } from '../api/endpoints'
import { tokenStore } from '../api/client'

/**
 * Web push subscription for the current browser.
 *
 * Three separate things have to line up before a user can be pushed to, and they fail in
 * different ways, so they are tracked separately rather than as one boolean:
 *  - the browser supports service workers + the Push API (Safari on iOS only does once the app
 *    has been added to the home screen),
 *  - the server has a VAPID keypair (otherwise there is nothing to subscribe against),
 *  - the user has granted notification permission and this browser holds a subscription.
 */

export type PushState = 'unsupported' | 'unconfigured' | 'denied' | 'off' | 'on'

/**
 * The Push API wants the server key as bytes, not as the base64url string the API returns.
 * Returned as an ArrayBuffer rather than a typed array: `applicationServerKey` is a BufferSource,
 * and a Uint8Array over an unspecified buffer does not satisfy it under strict lib types.
 */
function decodeKey(base64Url: string): ArrayBuffer {
  const padded = base64Url.padEnd(base64Url.length + ((4 - (base64Url.length % 4)) % 4), '=')
  const binary = atob(padded.replace(/-/g, '+').replace(/_/g, '/'))
  const buffer = new ArrayBuffer(binary.length)
  const bytes = new Uint8Array(buffer)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return buffer
}

function keyOf(subscription: PushSubscription, name: 'p256dh' | 'auth'): string {
  const raw = subscription.getKey(name)
  if (!raw) return ''
  let binary = ''
  new Uint8Array(raw).forEach((b) => (binary += String.fromCharCode(b)))
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

const supported = () =>
  typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window

export function useWebPush() {
  const [state, setState] = useState<PushState>('unsupported')
  const [busy, setBusy] = useState(false)

  const register = useCallback(async () => {
    const registration = await navigator.serviceWorker.register('/sw.js')
    // The worker fetches the notification body itself, so it needs the token — and it cannot
    // read localStorage. Hand it over on every load, which also refreshes it after a rotation.
    const token = tokenStore.getAccess()
    if (token) {
      const active = registration.active ?? (await navigator.serviceWorker.ready).active
      active?.postMessage({ type: 'TOKEN', token })
    }
    return registration
  }, [])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      if (!supported()) return
      try {
        const config = await pushConfig()
        if (cancelled) return
        if (!config.enabled) {
          setState('unconfigured')
          return
        }
        if (Notification.permission === 'denied') {
          setState('denied')
          return
        }
        const registration = await register()
        const existing = await registration.pushManager.getSubscription()
        if (!cancelled) setState(existing ? 'on' : 'off')
      } catch {
        // A failed probe must not break the page: the bell and the SSE stream are unaffected.
        if (!cancelled) setState('unsupported')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [register])

  const enable = useCallback(async () => {
    setBusy(true)
    try {
      const config = await pushConfig()
      if (!config.enabled) {
        setState('unconfigured')
        return
      }
      const permission = await Notification.requestPermission()
      if (permission !== 'granted') {
        setState(permission === 'denied' ? 'denied' : 'off')
        return
      }
      const registration = await register()
      const subscription =
        (await registration.pushManager.getSubscription()) ??
        (await registration.pushManager.subscribe({
          // Required by Chrome: every push must result in something the user can see.
          userVisibleOnly: true,
          applicationServerKey: decodeKey(config.publicKey),
        }))
      await subscribeToPush({
        endpoint: subscription.endpoint,
        p256dh: keyOf(subscription, 'p256dh'),
        auth: keyOf(subscription, 'auth'),
      })
      setState('on')
    } catch {
      setState('off')
    } finally {
      setBusy(false)
    }
  }, [register])

  const disable = useCallback(async () => {
    setBusy(true)
    try {
      const registration = await navigator.serviceWorker.ready
      const subscription = await registration.pushManager.getSubscription()
      if (subscription) {
        // Server first: if the browser drops it and the row survives, this service keeps pushing
        // into a dead endpoint until the push service reports it gone.
        await unsubscribeFromPush(subscription.endpoint)
        await subscription.unsubscribe()
      }
      setState('off')
    } catch {
      setState('off')
    } finally {
      setBusy(false)
    }
  }, [])

  return { state, busy, enable, disable }
}
