import { tokenStore } from './client'
import type { Notification } from './types'

// Live notification feed over SSE, so an alert reaches the bell the moment the backend writes it
// instead of waiting up to a poll interval.
//
// The browser's built-in `EventSource` cannot set an Authorization header, and putting the JWT in
// the query string would leak it into every access log along the way — so the stream is consumed
// with `fetch` and parsed by hand. It is a trivially simple format: events are separated by a
// blank line, and each line is `field: value`.

const ENDPOINT = '/api/v1/notifications/stream'

/** Delay before reconnecting, backing off from 1s to 30s so a dead backend is not hammered. */
const BACKOFF_START_MS = 1_000
const BACKOFF_MAX_MS = 30_000

export interface StreamHandlers {
  onNotification: (n: Notification) => void
  /** Fired on (re)connect, so the caller can reconcile anything missed while disconnected. */
  onOpen?: () => void
}

/**
 * Opens the stream and keeps it open, reconnecting with backoff. Returns a function that closes
 * it for good (call it on unmount).
 */
export function subscribeToNotifications(handlers: StreamHandlers): () => void {
  let closed = false
  let controller: AbortController | null = null
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  let backoff = BACKOFF_START_MS

  const scheduleReconnect = () => {
    if (closed) return
    retryTimer = setTimeout(connect, backoff)
    backoff = Math.min(backoff * 2, BACKOFF_MAX_MS)
  }

  const connect = async () => {
    if (closed) return
    const token = tokenStore.getAccess()
    if (!token) {
      scheduleReconnect() // not signed in yet — try again later
      return
    }

    controller = new AbortController()
    try {
      const res = await fetch(ENDPOINT, {
        headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
        signal: controller.signal,
      })
      if (!res.ok || !res.body) {
        scheduleReconnect()
        return
      }

      handlers.onOpen?.()

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      for (;;) {
        const { done, value } = await reader.read()
        if (done) break

        // Reset the backoff only once data actually flows. A 200 whose body ends immediately is
        // a broken stream, not a working one — resetting on the status code alone would turn it
        // into a 1-second reconnect loop instead of backing off.
        backoff = BACKOFF_START_MS
        buffer += decoder.decode(value, { stream: true })

        // A blank line terminates an event; anything before the last one is complete.
        const chunks = buffer.split('\n\n')
        buffer = chunks.pop() ?? ''
        for (const chunk of chunks) {
          dispatch(chunk, handlers)
        }
      }
    } catch {
      // Aborted by close(), or the connection dropped. Either way: reconnect unless closed.
    }
    scheduleReconnect()
  }

  connect()

  return () => {
    closed = true
    if (retryTimer) clearTimeout(retryTimer)
    controller?.abort()
  }
}

function dispatch(chunk: string, handlers: StreamHandlers) {
  let event = 'message'
  const dataLines: string[] = []

  for (const line of chunk.split('\n')) {
    if (line.startsWith(':')) continue // a comment — the server's heartbeat
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }

  if (event !== 'notification' || dataLines.length === 0) return
  try {
    handlers.onNotification(JSON.parse(dataLines.join('\n')) as Notification)
  } catch {
    // A malformed payload must not kill the stream.
  }
}
