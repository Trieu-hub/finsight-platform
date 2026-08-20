import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useInstallPrompt } from './useInstallPrompt'

/**
 * The Chromium event, close enough for the hook: it only ever calls these two members.
 *
 * `cancelable` is load-bearing — the real event is cancelable, and without it `preventDefault()`
 * is a silent no-op, so the test would report the mini-infobar as unsuppressed when it is not.
 */
function beforeInstallPromptEvent() {
  const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & {
    prompt: () => Promise<void>
    userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
  }
  event.prompt = vi.fn(async () => undefined)
  event.userChoice = Promise.resolve({ outcome: 'accepted' as const })
  return event
}

function setUserAgent(value: string, maxTouchPoints = 0) {
  Object.defineProperty(window.navigator, 'userAgent', { value, configurable: true })
  Object.defineProperty(window.navigator, 'maxTouchPoints', { value: maxTouchPoints, configurable: true })
}

const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36'
const IPHONE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Version/17.0 Safari/604.1'

describe('useInstallPrompt', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setUserAgent(DESKTOP_UA)
    // jsdom has no matchMedia; the hook guards for it, and the guard is what this stub exercises.
    window.matchMedia = vi.fn().mockReturnValue({ matches: false }) as unknown as typeof matchMedia
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('offers nothing until the browser says the app is installable', () => {
    const { result } = renderHook(() => useInstallPrompt())

    // Chromium fires the event only once it considers the app installable AND the visitor
    // engaged — which may be seconds in, or never. Showing a button before that would open a
    // dialog that does not exist.
    expect(result.current.offer).toBe('none')
  })

  it('offers the real prompt once the event arrives', async () => {
    const { result } = renderHook(() => useInstallPrompt())
    const event = beforeInstallPromptEvent()

    await act(async () => {
      window.dispatchEvent(event)
    })

    expect(result.current.offer).toBe('prompt')
    // Suppressing Chromium's own mini-infobar is the whole point: it cannot be recalled once
    // dismissed, so leaving it up would spend the one chance to ask.
    expect(event.defaultPrevented).toBe(true)
  })

  it('opens the browser dialog and then stands down', async () => {
    const { result } = renderHook(() => useInstallPrompt())
    const event = beforeInstallPromptEvent()
    await act(async () => {
      window.dispatchEvent(event)
    })

    await act(async () => {
      await result.current.install()
    })

    expect(event.prompt).toHaveBeenCalled()
    // The event is single-use — Chromium rejects a second prompt() on the same one.
    expect(result.current.offer).toBe('none')
  })

  it('remembers a dismissal, so it never asks twice', async () => {
    const first = renderHook(() => useInstallPrompt())
    await act(async () => {
      window.dispatchEvent(beforeInstallPromptEvent())
    })
    act(() => first.result.current.dismiss())
    expect(first.result.current.offer).toBe('none')

    // A fresh mount is what a page reload looks like to the hook.
    const second = renderHook(() => useInstallPrompt())
    await act(async () => {
      window.dispatchEvent(beforeInstallPromptEvent())
    })

    expect(second.result.current.offer).toBe('none')
  })

  it('falls back to instructions on iOS, which fires no event at all', () => {
    setUserAgent(IPHONE_UA)

    const { result } = renderHook(() => useInstallPrompt())

    expect(result.current.offer).toBe('ios')
  })

  it('recognises an iPad, which claims to be a Mac', () => {
    setUserAgent('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Version/17.0 Safari/605.1', 5)

    const { result } = renderHook(() => useInstallPrompt())

    expect(result.current.offer).toBe('ios')
  })

  it('says nothing once the app is already installed', () => {
    setUserAgent(IPHONE_UA)
    Object.defineProperty(window.navigator, 'standalone', { value: true, configurable: true })

    const { result } = renderHook(() => useInstallPrompt())

    expect(result.current.offer).toBe('none')
    Object.defineProperty(window.navigator, 'standalone', { value: undefined, configurable: true })
  })

  it('drops the offer the moment the install completes', async () => {
    const { result } = renderHook(() => useInstallPrompt())
    await act(async () => {
      window.dispatchEvent(beforeInstallPromptEvent())
    })
    expect(result.current.offer).toBe('prompt')

    await act(async () => {
      window.dispatchEvent(new Event('appinstalled'))
    })

    expect(result.current.offer).toBe('none')
  })
})
