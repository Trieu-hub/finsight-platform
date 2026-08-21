import { describe, expect, it } from 'vitest'
import { loadFailure, valueOr } from './settled'

const ok = <T>(value: T): PromiseSettledResult<T> => ({ status: 'fulfilled', value })
const bad = (reason: unknown): PromiseSettledResult<never> => ({ status: 'rejected', reason })

describe('settled', () => {
  it('keeps what arrived', () => {
    expect(valueOr(ok([1, 2]), [])).toEqual([1, 2])
  })

  it('falls back for what did not', () => {
    // The whole point: a missing figure becomes an empty list, not an exception that discards
    // the three answers beside it.
    expect(valueOr(bad(new Error('nope')), [])).toEqual([])
  })

  it('reports nothing when everything arrived', () => {
    expect(loadFailure([ok(1), ok(2)], true)).toBeNull()
  })

  it('reports a failure while online, exactly as before', () => {
    const reason = new Error('500')
    expect(loadFailure([ok(1), bad(reason)], true)).toBe(reason)
  })

  it('stays quiet about a partial failure while offline', () => {
    // The offline banner already explains stale figures. An error on top of it would tell the
    // user the app is broken while it is doing precisely what it promised.
    expect(loadFailure([ok(1), bad(new Error('cache miss'))], false)).toBeNull()
  })

  it('still speaks up when offline leaves nothing at all to show', () => {
    const reason = new Error('cache miss')
    // No page to look at — silence here would be an empty screen with no explanation.
    expect(loadFailure([bad(reason), bad(reason)], false)).toBe(reason)
  })
})
