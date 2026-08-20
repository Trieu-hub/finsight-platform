/*
 * Service worker: web push, and a read-only offline cache.
 *
 * == Push ==
 * The pushes this app sends carry NO payload — deliberately, so the alert text never passes
 * through Google's or Mozilla's push infrastructure. The bare push is only a nudge; the actual
 * content is fetched here over the normal API.
 *
 * That fetch needs the user's token, and a service worker cannot read localStorage. So the page
 * hands its token over on registration (and on every load, which also refreshes it after a
 * rotation) and this worker keeps it in memory. Losing it on worker restart is fine: the fallback
 * below still shows a generic notification, and the next page load re-arms it.
 *
 * == Offline ==
 * Read-only, and deliberately so. Losing the network gets you the app shell and the figures from
 * the last time you loaded them, instead of the browser's error page. It does NOT get you a
 * queue of pending writes: a transaction composed offline and replayed later would land with the
 * wrong date, race the balance the server keeps, and reach budgets and risk rules out of order.
 * Every non-GET therefore fails loudly while offline, which is the honest answer.
 *
 * The cached API responses are one user's financial data sitting on disk, so the page sends
 * PURGE whenever it clears its tokens. Without that, signing out on a shared browser would
 * leave the next person a readable copy.
 */

const VERSION = 'v1'
const SHELL_CACHE = `vernfy-shell-${VERSION}`
const API_CACHE = `vernfy-api-${VERSION}`

/* Enough to boot the SPA offline. React Router resolves the actual route client-side. */
const SHELL_URLS = ['/', '/manifest.json', '/favicon.svg']

/*
 * Read endpoints worth keeping a copy of — the screens a user opens to *look* at something.
 * Everything absent is absent on purpose: /auth carries tokens, /notifications and /push must
 * reflect the server right now, /game is server-authoritative (a replayed spin result would be
 * a lie), and /transactions/export is a file download that means nothing stale.
 */
const CACHEABLE_API = [
  '/api/v1/analytics',
  '/api/v1/budgets',
  '/api/v1/categories',
  '/api/v1/transactions',
  '/api/v1/wallets',
]

function isCacheableApi(pathname) {
  if (pathname.startsWith('/api/v1/transactions/export')) return false
  return CACHEABLE_API.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`) || pathname.startsWith(`${prefix}?`),
  )
}

let token = null

self.addEventListener('message', (event) => {
  if (!event.data) return
  if (event.data.type === 'TOKEN') {
    token = event.data.token
  }
  if (event.data.type === 'PURGE') {
    token = null
    event.waitUntil(caches.delete(API_CACHE))
  }
  // The page asks for the handover, rather than this worker taking it. See the install listener.
  if (event.data.type === 'SKIP_WAITING') {
    self.skipWaiting()
  }
})

/**
 * Primes the shell cache at install time — including the JS and CSS bundles, which is the part
 * that is easy to get wrong.
 *
 * The very first page load happens *before* this worker controls anything, so its `fetch`
 * listener never sees the bundle request and never caches it. Left there, a first-time visitor
 * who then goes offline gets the cached index.html and a script tag pointing at a file that was
 * never stored: a blank page, which looks exactly like a broken app. Vite content-hashes those
 * names so they cannot be listed as constants, so they are read off the shell HTML instead.
 */
async function primeShell() {
  const cache = await caches.open(SHELL_CACHE)
  await cache.addAll(SHELL_URLS)
  const shell = await cache.match('/')
  if (!shell) return
  const html = await shell.clone().text()
  const assets = [...html.matchAll(/(?:src|href)="(\/assets\/[^"]+)"/g)].map((match) => match[1])
  if (assets.length > 0) await cache.addAll(assets)
}

self.addEventListener('install', (event) => {
  // Never let a missing file fail the install: push is the load-bearing half of this worker and
  // must keep working even if the shell could not be primed.
  event.waitUntil(primeShell().catch(() => undefined))
  // NO skipWaiting here, deliberately. Taking over mid-session swaps the cached bundles under a
  // page that is still running: React then asks for a lazily-imported chunk whose hashed name the
  // new build no longer has, and the user gets "Failed to fetch dynamically imported module" —
  // during a deploy, on a screen they were using. Instead the new worker waits, the page notices
  // it waiting and offers a reload, and the handover happens when the user says so (SKIP_WAITING
  // above). The cost is that a new version reaches an open tab one click later than it could.
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys()
      await Promise.all(
        keys
          .filter((key) => key.startsWith('vernfy-') && key !== SHELL_CACHE && key !== API_CACHE)
          .map((key) => caches.delete(key)),
      )
      await self.clients.claim()
    })(),
  )
})

/** Cache-first. Only for `/assets/` — Vite fingerprints those names, so a hit is never stale. */
async function cacheFirst(request) {
  const cache = await caches.open(SHELL_CACHE)
  // ignoreVary because the copy stored at install time came from `cache.addAll`, whose request
  // carries different headers than the page's own <script>/<link> fetch. Without it the entry is
  // there but never matches, and the app is blank offline — with no error to say why.
  const cached = await cache.match(request, { ignoreVary: true })
  if (cached) return cached
  const response = await fetch(request)
  if (response.ok) {
    await cache.put(request, response.clone())
  }
  return response
}

/**
 * Network-first: the network's answer always wins when there is one, so a working connection
 * never shows yesterday's numbers. The cache is strictly a fallback.
 *
 * Lookups go through the named cache rather than `caches.match`, which searches every cache in
 * the origin — including whatever another app on the same host has stored.
 */
async function networkFirst(request, cacheName, fallbackRequest) {
  const cache = await caches.open(cacheName)
  try {
    const response = await fetch(request)
    // Only 2xx is worth storing. Caching a 401 would keep serving it after the token is renewed.
    if (response.ok) {
      await cache.put(request, response.clone())
    }
    return response
  } catch (error) {
    // The API request carries an Authorization header the stored response may Vary on; ignoreVary
    // keeps a fallback available. Safe here because the page purges this cache on sign-out, so a
    // stored entry always belongs to the user who is still signed in.
    const cached = await cache.match(fallbackRequest ?? request, { ignoreVary: true })
    if (cached) return cached
    throw error
  }
}

self.addEventListener('fetch', (event) => {
  const request = event.request
  // A write must never be answered from a cache, and a cross-origin request is none of our
  // business. Returning without respondWith leaves the browser's own handling untouched.
  if (request.method !== 'GET') return

  let url
  try {
    url = new URL(request.url)
  } catch {
    return
  }
  if (url.origin !== self.location.origin) return

  if (request.mode === 'navigate') {
    // Any route falls back to the cached shell, matching nginx's try_files.
    event.respondWith(networkFirst(request, SHELL_CACHE, '/'))
    return
  }
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(cacheFirst(request))
    return
  }
  if (isCacheableApi(url.pathname)) {
    event.respondWith(networkFirst(request, API_CACHE))
  }
})

async function latestUnread() {
  if (!token) return null
  try {
    const res = await fetch('/api/v1/notifications?unreadOnly=true&page=1&limit=1', {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    })
    if (!res.ok) return null
    const body = await res.json()
    return body?.data?.[0] ?? null
  } catch {
    return null
  }
}

self.addEventListener('push', (event) => {
  event.waitUntil(
    (async () => {
      const notification = await latestUnread()
      // A generic title is the honest fallback when the token is gone or the API is unreachable:
      // the user still learns something happened, and clicking through shows them what.
      const title = notification?.title ?? 'Vernfy'
      const body = notification?.message ?? 'You have a new alert.'
      await self.registration.showNotification(title, {
        body,
        icon: '/favicon.svg',
        badge: '/favicon.svg',
        tag: notification?.id ?? 'vernfy-alert',
        data: { url: '/' },
      })
    })(),
  )
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  event.waitUntil(
    (async () => {
      const url = event.notification.data?.url ?? '/'
      const clients = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      // Focus a tab that is already open rather than piling up new ones.
      for (const client of clients) {
        if ('focus' in client) {
          await client.focus()
          if ('navigate' in client) await client.navigate(url)
          return
        }
      }
      await self.clients.openWindow(url)
    })(),
  )
})
