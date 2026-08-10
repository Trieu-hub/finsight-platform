import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useOnline } from './useOnline'

function setOnLine(value: boolean) {
  Object.defineProperty(navigator, 'onLine', { value, configurable: true })
}

afterEach(() => {
  setOnLine(true)
})

describe('useOnline', () => {
  it('starts from whatever the browser already thinks', () => {
    setOnLine(false)
    const { result } = renderHook(() => useOnline())
    expect(result.current).toBe(false)
  })

  it('follows the offline and online events', () => {
    const { result } = renderHook(() => useOnline())
    expect(result.current).toBe(true)

    act(() => {
      setOnLine(false)
      window.dispatchEvent(new Event('offline'))
    })
    expect(result.current).toBe(false)

    act(() => {
      setOnLine(true)
      window.dispatchEvent(new Event('online'))
    })
    expect(result.current).toBe(true)
  })

  it('stops listening once unmounted, so a late event cannot set state on a dead component', () => {
    const { unmount } = renderHook(() => useOnline())
    unmount()
    // Would warn (or throw under StrictMode) if the listeners were still attached.
    expect(() => window.dispatchEvent(new Event('offline'))).not.toThrow()
  })
})
