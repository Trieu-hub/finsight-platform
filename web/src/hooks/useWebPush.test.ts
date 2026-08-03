import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// jsdom ships neither a service worker nor a PushManager, so every capability the hook probes has
// to be planted by hand. What is under test is which of the five outcomes the hook lands on —
// above all that "this browser cannot do push" and "we could not find out" stay apart.
vi.mock('../api/endpoints', () => ({
  pushConfig: vi.fn(),
  subscribeToPush: vi.fn(),
  unsubscribeFromPush: vi.fn(),
}))

const { pushConfig, subscribeToPush } = await import('../api/endpoints')
const { useWebPush } = await import('./useWebPush')

const SUBSCRIPTION = {
  endpoint: 'https://push.example/abc',
  getKey: () => new Uint8Array([1, 2, 3]).buffer,
  unsubscribe: vi.fn(),
}

function pushCapableBrowser(existing: unknown = null) {
  const pushManager = {
    getSubscription: vi.fn().mockResolvedValue(existing),
    subscribe: vi.fn().mockResolvedValue(SUBSCRIPTION),
  }
  const registration = { active: null, pushManager }
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      register: vi.fn().mockResolvedValue(registration),
      ready: Promise.resolve(registration),
    },
  })
  vi.stubGlobal('PushManager', class {})
  vi.stubGlobal('Notification', {
    permission: 'default',
    requestPermission: vi.fn().mockResolvedValue('granted'),
  })
  return pushManager
}

describe('useWebPush', () => {
  beforeEach(() => {
    vi.mocked(pushConfig).mockReset().mockResolvedValue({ enabled: true, publicKey: 'BAECAw' })
    vi.mocked(subscribeToPush).mockReset().mockResolvedValue(undefined)
    // Spied rather than left alone: the hook logs on purpose, and the log is what the tests below
    // check is still carrying the cause.
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    Reflect.deleteProperty(navigator, 'serviceWorker')
  })

  it('stays unsupported when the browser has no Push API, without asking the server', async () => {
    const { result } = renderHook(() => useWebPush())

    await waitFor(() => expect(result.current.state).toBe('unsupported'))
    expect(pushConfig).not.toHaveBeenCalled()
  })

  it('tells a failed probe apart from an unsupported browser, and keeps the cause', async () => {
    pushCapableBrowser()
    vi.mocked(pushConfig).mockRejectedValue(new Error('Network Error'))

    const { result } = renderHook(() => useWebPush())

    await waitFor(() => expect(result.current.state).toBe('error'))
    expect(vi.mocked(console.warn)).toHaveBeenCalledWith('[push] probe failed', expect.any(Error))
  })

  it('reports a server without a VAPID keypair as unconfigured', async () => {
    pushCapableBrowser()
    vi.mocked(pushConfig).mockResolvedValue({ enabled: false, publicKey: '' })

    const { result } = renderHook(() => useWebPush())

    await waitFor(() => expect(result.current.state).toBe('unconfigured'))
  })

  it('reports a browser-level denial, which the page cannot undo', async () => {
    pushCapableBrowser()
    vi.stubGlobal('Notification', { permission: 'denied' })

    const { result } = renderHook(() => useWebPush())

    await waitFor(() => expect(result.current.state).toBe('denied'))
  })

  it('is off without a subscription and on with one', async () => {
    pushCapableBrowser()
    const { result, unmount } = renderHook(() => useWebPush())
    await waitFor(() => expect(result.current.state).toBe('off'))
    unmount()

    pushCapableBrowser(SUBSCRIPTION)
    const already = renderHook(() => useWebPush())
    await waitFor(() => expect(already.result.current.state).toBe('on'))
  })

  it('keeps the switch on screen when subscribing fails', async () => {
    const pushManager = pushCapableBrowser()
    pushManager.subscribe.mockRejectedValue(new Error('AbortError: Registration failed'))
    const { result } = renderHook(() => useWebPush())
    await waitFor(() => expect(result.current.state).toBe('off'))

    await act(() => result.current.enable())

    // 'error' here would hide the row the user just clicked; 'off' leaves it there to press again.
    expect(result.current.state).toBe('off')
    expect(subscribeToPush).not.toHaveBeenCalled()
    expect(vi.mocked(console.warn)).toHaveBeenCalledWith(
      '[push] subscribe failed',
      expect.any(Error),
    )
  })
})
