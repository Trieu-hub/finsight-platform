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

export function registerServiceWorker() {
  if (!enabled()) return
  // Registration competes with the first paint for bandwidth; waiting for load costs nothing
  // because nothing on screen depends on it.
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((cause) => {
      // Offline is an enhancement. Say why in the console and carry on.
      console.warn('[sw] registration failed', cause)
    })
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
