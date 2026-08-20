/**
 * Service worker registration, for the offline cache rather than for push.
 *
 * `useWebPush` also registers `/sw.js`, but only once a user turns push on. Offline has to work
 * for everyone, so registration happens at startup too — `register()` with the same URL is
 * idempotent, so the two callers share one registration rather than fighting over it.
 */

/**
 * Dev only ever runs from Vite's dev server, where the shell and modules are served unhashed and
 * change on every save; a worker caching them is a debugging trap, not a feature. `vite preview`
 * (what the Playwright journeys drive) builds production, so the offline path is still exercised.
 */
const enabled = () =>
  typeof navigator !== 'undefined' && 'serviceWorker' in navigator && import.meta.env.PROD

/** Fired when a new worker has installed and is waiting for permission to take over. */
export const SW_UPDATE_EVENT = 'vernfy:sw-update'

/**
 * Announces a waiting worker to the page. A CustomEvent rather than a callback argument because
 * the two callers of `registerServiceWorker` (startup and `useWebPush`) share one registration —
 * whoever registered first should not decide who gets told.
 */
function announceUpdate() {
  window.dispatchEvent(new CustomEvent(SW_UPDATE_EVENT))
}

/**
 * Watches one registration for a worker that has installed behind the current one.
 *
 * The `controller` check is what separates "a new version is ready" from "this is the very first
 * install": on a first visit there is no controller, the worker activates immediately, and telling
 * the user to reload for a version they are already running would be nonsense.
 */
function watchForUpdate(registration: ServiceWorkerRegistration) {
  if (registration.waiting && navigator.serviceWorker.controller) {
    announceUpdate()
  }
  registration.addEventListener('updatefound', () => {
    const installing = registration.installing
    if (!installing) return
    installing.addEventListener('statechange', () => {
      if (installing.state === 'installed' && navigator.serviceWorker.controller) {
        announceUpdate()
      }
    })
  })
}

export function registerServiceWorker() {
  if (!enabled()) return
  // Registration competes with the first paint for bandwidth; waiting for load costs nothing
  // because nothing on screen depends on it.
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js')
      .then(watchForUpdate)
      .catch((cause) => {
        // Offline is an enhancement. Say why in the console and carry on.
        console.warn('[sw] registration failed', cause)
      })
  })
}

/**
 * Hands the page over to the waiting worker, then reloads onto it.
 *
 * The reload is driven by `controllerchange` rather than fired straight after the message: the
 * new worker has to activate first, and reloading before it does would just re-run the old one
 * and leave the update still waiting.
 */
export function applyServiceWorkerUpdate() {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  navigator.serviceWorker.getRegistration().then((registration) => {
    const waiting = registration?.waiting
    if (!waiting) {
      // Nothing to hand over to — reload anyway, so the button is never a dead end.
      window.location.reload()
      return
    }
    navigator.serviceWorker.addEventListener('controllerchange', () => window.location.reload(), {
      once: true,
    })
    waiting.postMessage({ type: 'SKIP_WAITING' })
  })
}

/**
 * Drops the cached API responses. Called wherever the app clears its tokens: those responses are
 * one user's financial data, and leaving them behind would hand the next person on a shared
 * browser a readable copy of the last session.
 */
export function purgeCachedData() {
  if (typeof navigator === 'undefined' || !('serviceWorker' in navigator)) return
  navigator.serviceWorker.controller?.postMessage({ type: 'PURGE' })
}
