import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'
import { createTransaction } from '../api/endpoints'
import { flush, queuedCount, type QueuedTransaction } from '../lib/outbox'

/**
 * Drains the offline outbox when the network comes back, and reports what is still waiting.
 *
 * Driven by the `online` event rather than the Background Sync API: Background Sync is
 * Chromium-only (Safari has never shipped it), and this app's users are on iPhones. An event the
 * page can hear works everywhere, at the cost of only draining while a tab is open — which is
 * also when the user is there to see it happen.
 */

/**
 * A rejection the server will not change its mind about. Retrying a validation error forever
 * would pin the queue and block everything behind it, so those are dropped rather than kept.
 * A 408 or 429 is explicitly NOT permanent — those mean "later", which is what a queue is for.
 */
function isPermanentRejection(error: unknown): boolean {
  if (!axios.isAxiosError(error)) return false
  const status = error.response?.status
  if (status === undefined) return false // no response at all = the network, not the server
  if (status === 408 || status === 429) return false
  return status >= 400 && status < 500
}

const send = async (item: QueuedTransaction) => {
  await createTransaction({ ...item.body, clientRequestId: item.clientRequestId })
}

/** @param onDrained runs when at least one queued write actually landed, so the page can reload. */
export function useOutbox(onDrained?: () => void) {
  const [pending, setPending] = useState(() => queuedCount())

  // Held in a ref so `drain` stays referentially stable. Callers pass an inline function that is
  // new on every render; as a dependency it would re-register the `online` listener each time.
  const onDrainedRef = useRef(onDrained)
  useEffect(() => {
    onDrainedRef.current = onDrained
  })

  const drain = useCallback(async () => {
    if (queuedCount() === 0) return
    const sent = await flush(send, isPermanentRejection)
    setPending(queuedCount())
    if (sent > 0) onDrainedRef.current?.()
  }, [])

  useEffect(() => {
    // On mount as well as on `online`: the tab may have been closed while offline and reopened
    // with a connection, in which case no `online` event ever fires.
    void drain()
    const onOnline = () => void drain()
    window.addEventListener('online', onOnline)
    return () => window.removeEventListener('online', onOnline)
  }, [drain])

  /** Called by the form after it queues something, so the badge updates without a reload. */
  const refresh = useCallback(() => setPending(queuedCount()), [])

  return { pending, refresh, drain }
}
