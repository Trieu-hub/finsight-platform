import { beforeEach, describe, expect, it, vi } from 'vitest'

// The worker is evaluated from its real source rather than re-implemented here: sw.js ships as a
// static file that nothing imports, so a test that mirrored its logic would keep passing after the
// shipped file broke. `?raw` + a fresh scope per test gives us the actual listeners it registers.
import swSource from '../public/sw.js?raw'

type Listener = (event: unknown) => void

interface FakeCache {
  store: Map<string, Response>
  match: (request: unknown, options?: { ignoreVary?: boolean }) => Promise<Response | undefined>
  put: (request: unknown, response: Response) => Promise<void>
  addAll: (urls: string[]) => Promise<void>
}

const keyOf = (request: unknown) =>
  typeof request === 'string' ? request : ((request as Request).url ?? String(request))

function makeCache(bodies: Record<string, string> = {}): FakeCache {
  const store = new Map<string, Response>()
  return {
    store,
    match: async (request) => store.get(keyOf(request)),
    put: async (request, response) => {
      store.set(keyOf(request), response)
    },
    addAll: async (urls) => {
      for (const url of urls) store.set(url, new Response(bodies[url] ?? 'shell'))
    },
  }
}

/** Boots sw.js in an isolated fake worker scope and hands back everything a test needs to poke it. */
function bootWorker(bodies: Record<string, string> = {}) {
  const listeners = new Map<string, Listener>()
  const caches = new Map<string, FakeCache>()
  const deleted: string[] = []

  const cachesApi = {
    open: async (name: string) => {
      if (!caches.has(name)) caches.set(name, makeCache(bodies))
      return caches.get(name)!
    },
    keys: async () => [...caches.keys()],
    delete: async (name: string) => {
      deleted.push(name)
      return caches.delete(name)
    },
  }

  const self = {
    addEventListener: (type: string, listener: Listener) => listeners.set(type, listener),
    skipWaiting: vi.fn(),
    clients: { claim: vi.fn(async () => undefined), matchAll: vi.fn(async () => []) },
    registration: { showNotification: vi.fn(async () => undefined) },
    location: { origin: 'https://vernfy.com' },
  }

  new Function('self', 'caches', 'fetch', 'Response', 'URL', swSource)(
    self,
    cachesApi,
    (...args: unknown[]) => globalThis.fetch(...(args as Parameters<typeof globalThis.fetch>)),
    Response,
    URL,
  )

  /** Runs the fetch listener and returns what it answered, or null when it stayed out of the way. */
  async function handleFetch(request: Partial<Request> & { url: string }) {
    const responded: Promise<Response>[] = []
    listeners.get('fetch')!({
      request: { method: 'GET', mode: 'cors', ...request },
      respondWith: (promise: Promise<Response>) => responded.push(promise),
      waitUntil: (promise: Promise<unknown>) => promise,
    })
    return responded.length === 0 ? null : await responded[0]
  }

  /**
   * Dispatches a non-fetch event and awaits whatever it handed to `waitUntil`. Without the await
   * the assertions race the worker's own async work and pass for the wrong reason.
   */
  async function dispatch(type: string, data?: unknown) {
    const pending: Promise<unknown>[] = []
    await listeners.get(type)!({
      data,
      waitUntil: (promise: Promise<unknown>) => pending.push(promise),
    })
    await Promise.all(pending)
  }

  return { listeners, caches, cachesApi, deleted, self, handleFetch, dispatch }
}

const API_CACHE = 'vernfy-api-v1'
const SHELL_CACHE = 'vernfy-shell-v1'

describe('service worker offline cache', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('serves the last good API response when the network is gone', async () => {
    const worker = bootWorker()
    const url = 'https://vernfy.com/api/v1/transactions?page=1'

    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ data: ['online'] }), { status: 200 }),
    )
    const fresh = await worker.handleFetch({ url })
    expect(await fresh!.json()).toEqual({ data: ['online'] })

    // Now the network is gone: the same request must be answered from the copy just stored.
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const offline = await worker.handleFetch({ url })
    expect(await offline!.json()).toEqual({ data: ['online'] })
  })

  it('fails rather than inventing an answer when nothing was ever cached', async () => {
    const worker = bootWorker()
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(
      worker.handleFetch({ url: 'https://vernfy.com/api/v1/budgets' }),
    ).rejects.toThrow(/Failed to fetch/)
  })

  it('never caches a write, so an offline edit cannot look like it succeeded', async () => {
    const worker = bootWorker()
    const answered = await worker.handleFetch({
      url: 'https://vernfy.com/api/v1/transactions',
      method: 'POST',
    })
    // No respondWith at all — the browser's own handling stands, and the POST fails offline.
    expect(answered).toBeNull()
  })

  it('leaves auth, notifications, push and game requests alone', async () => {
    const worker = bootWorker()
    // Mocked so that a worker which *wrongly* claims one of these fails on the assertion below
    // rather than on the fetch, which would report a confusing parse error instead of the point.
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 200 }))
    for (const path of [
      '/api/v1/auth/login',
      '/api/v1/notifications',
      '/api/v1/push/subscriptions',
      '/api/v1/game/roulette/status',
      '/api/v1/transactions/export',
    ]) {
      expect(await worker.handleFetch({ url: `https://vernfy.com${path}` }), path).toBeNull()
    }
  })

  it('ignores another origin entirely', async () => {
    const worker = bootWorker()
    expect(
      await worker.handleFetch({ url: 'https://example.com/api/v1/transactions' }),
    ).toBeNull()
  })

  it('does not store an error response, which would outlive the token that caused it', async () => {
    const worker = bootWorker()
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('nope', { status: 401 }))
    await worker.handleFetch({ url: 'https://vernfy.com/api/v1/wallets' })

    const cache = await worker.cachesApi.open(API_CACHE)
    expect(cache.store.size).toBe(0)
  })

  it('falls back to the cached shell for any route, matching nginx try_files', async () => {
    const worker = bootWorker()
    const shell = await worker.cachesApi.open(SHELL_CACHE)
    await shell.addAll(['/'])

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const answered = await worker.handleFetch({
      url: 'https://vernfy.com/wallets',
      mode: 'navigate',
    })

    expect(await answered!.text()).toBe('shell')
  })

  it('drops the API cache on PURGE, so a sign-out leaves nothing readable behind', async () => {
    const worker = bootWorker()
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('{}', { status: 200 }))
    await worker.handleFetch({ url: 'https://vernfy.com/api/v1/budgets' })
    expect((await worker.cachesApi.open(API_CACHE)).store.size).toBe(1)

    await worker.dispatch('message', { type: 'PURGE' })

    expect(worker.deleted).toContain(API_CACHE)
  })

  /**
   * The first visit is not controlled by the worker, so its bundle request is never intercepted
   * and never cached. A browser test caught exactly that: the shell came back offline and the
   * page was blank, because index.html pointed at a script nothing had stored.
   */
  it('caches the hashed bundles named in the shell, which the first load never routes through it', async () => {
    const shell =
      '<!doctype html><html><head><link rel="stylesheet" href="/assets/index-abc123.css">' +
      '<link rel="manifest" href="/manifest.json"></head>' +
      '<body><script type="module" src="/assets/index-def456.js"></script></body></html>'
    const worker = bootWorker({ '/': shell })

    await worker.dispatch('install')

    const cache = await worker.cachesApi.open(SHELL_CACHE)
    expect([...cache.store.keys()]).toEqual(
      expect.arrayContaining(['/', '/assets/index-abc123.css', '/assets/index-def456.js']),
    )
  })

  it('clears caches from an older version on activate', async () => {
    const worker = bootWorker()
    await worker.cachesApi.open('vernfy-api-v0')
    await worker.cachesApi.open('vernfy-shell-v1')

    await worker.dispatch('activate')

    expect(worker.deleted).toContain('vernfy-api-v0')
    expect(worker.deleted).not.toContain('vernfy-shell-v1')
  })

  /*
   * A new worker must NOT take over a page that is already running. Doing so swaps the cached
   * bundles under it, and the next lazily-imported route asks for a hashed chunk the new build no
   * longer has — "Failed to fetch dynamically imported module", mid-deploy, on a live screen.
   */
  it('does not take over on install, however new it is', async () => {
    const worker = bootWorker({ '/': '<!doctype html><html></html>' })

    await worker.dispatch('install')

    expect(worker.self.skipWaiting).not.toHaveBeenCalled()
  })

  it('hands over only when the page asks it to', async () => {
    const worker = bootWorker()

    await worker.dispatch('message', { type: 'SKIP_WAITING' })

    expect(worker.self.skipWaiting).toHaveBeenCalled()
  })

  it('ignores a message it does not understand', async () => {
    const worker = bootWorker()

    await worker.dispatch('message', { type: 'SOMETHING_ELSE' })

    expect(worker.self.skipWaiting).not.toHaveBeenCalled()
  })
})
