/*
 * Service worker for web push.
 *
 * The pushes this app sends carry NO payload — deliberately, so the alert text never passes
 * through Google's or Mozilla's push infrastructure. The bare push is only a nudge; the actual
 * content is fetched here over the normal API.
 *
 * That fetch needs the user's token, and a service worker cannot read localStorage. So the page
 * hands its token over on registration (and on every load, which also refreshes it after a
 * rotation) and this worker keeps it in memory. Losing it on worker restart is fine: the fallback
 * below still shows a generic notification, and the next page load re-arms it.
 */

let token = null

self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'TOKEN') {
    token = event.data.token
  }
})

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

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
