import type { TransactionType } from '../api/types'

/**
 * A queue of transactions composed while offline, replayed when the network returns.
 *
 * The service worker deliberately refuses to answer a write from cache — see `public/sw.js`. That
 * is still right: a cached "created" response would be a lie. This queue is the other half of the
 * answer, and it only works because of two things the API already provides:
 *
 * - **`transactionDate` is supplied by the client**, so a write replayed on Thursday still lands
 *   on the Tuesday the user chose. Nothing drifts.
 * - **`clientRequestId` makes a replay idempotent** (transaction-service V14, unique per user).
 *   The queue can never know whether a request that failed mid-flight was actually applied, so
 *   the only safe design is one where sending it again is harmless.
 *
 * Stored in `localStorage`, not IndexedDB: this holds a handful of small objects, survives a
 * reload, and is synchronous — which keeps the replay logic readable. If it ever needs to hold
 * attachments or thousands of rows, that is the point to move it.
 */

export interface QueuedTransaction {
  clientRequestId: string
  queuedAt: number
  body: {
    type: TransactionType
    amount: number
    currency: string
    categoryId: number
    description?: string
    transactionDate: string
    walletId?: number
    toWalletId?: number
    budgetId?: string
  }
}

const KEY = 'vernfy.outbox.transactions'

/** Cap so a long offline stretch cannot fill the origin's storage quota. */
const MAX_QUEUED = 100

function read(): QueuedTransaction[] {
  try {
    const raw = window.localStorage.getItem(KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    // Corrupt or blocked storage must not take the page down; an unreadable queue is an empty one.
    return []
  }
}

function write(items: QueuedTransaction[]): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(items))
  } catch {
    // Quota or private mode. The write is lost, which is bad — but throwing here would lose the
    // user's whole form as well, which is worse.
  }
}

export function queued(): QueuedTransaction[] {
  return read()
}

export function queuedCount(): number {
  return read().length
}

/**
 * Adds one transaction to the queue and returns it, or null when the queue is full.
 *
 * The id is generated here rather than server-side for the obvious reason: there is no server
 * to ask. `crypto.randomUUID` is available in every browser that can run a service worker.
 */
export function enqueue(body: QueuedTransaction['body']): QueuedTransaction | null {
  const items = read()
  if (items.length >= MAX_QUEUED) return null

  const item: QueuedTransaction = {
    clientRequestId: crypto.randomUUID(),
    queuedAt: Date.now(),
    body,
  }
  write([...items, item])
  return item
}

export function remove(clientRequestId: string): void {
  write(read().filter((item) => item.clientRequestId !== clientRequestId))
}

/** Drops everything. Called on sign-out: a queue belongs to the account that filled it. */
export function clearOutbox(): void {
  try {
    window.localStorage.removeItem(KEY)
  } catch {
    // Nothing to do — see write().
  }
}

/**
 * Sends every queued transaction, oldest first, and returns how many landed.
 *
 * A send that *fails* leaves the item queued and stops the run: if the network is still down,
 * hammering the rest achieves nothing, and if the server rejected this one for a reason it will
 * reject the next attempt too. The exception is a rejection the server will never change its mind
 * about — a 4xx that is not a timeout — where retrying forever would pin the queue permanently;
 * those are dropped so the rest can drain.
 */
export async function flush(
  send: (item: QueuedTransaction) => Promise<void>,
  isPermanentRejection: (error: unknown) => boolean,
): Promise<number> {
  let sent = 0
  for (const item of read()) {
    try {
      await send(item)
      remove(item.clientRequestId)
      sent++
    } catch (error) {
      if (isPermanentRejection(error)) {
        remove(item.clientRequestId)
        continue
      }
      break
    }
  }
  return sent
}
