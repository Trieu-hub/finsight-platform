import { describe, expect, it } from 'vitest'
import { monthRange } from './download'

describe('monthRange', () => {
  it('covers a whole 31-day month', () => {
    expect(monthRange('2026-07')).toEqual({ fromDate: '2026-07-01', toDate: '2026-07-31' })
  })

  it('covers a 30-day month', () => {
    expect(monthRange('2026-06')).toEqual({ fromDate: '2026-06-01', toDate: '2026-06-30' })
  })

  it('gets February right in a common year and a leap year', () => {
    // The reason the last day is computed rather than assumed: a hard-coded 28 loses a day of
    // transactions every four years, and only in February.
    expect(monthRange('2026-02').toDate).toBe('2026-02-28')
    expect(monthRange('2028-02').toDate).toBe('2028-02-29')
  })

  it('returns an empty range for "all months"', () => {
    // No dates means no date filter, which is what the export endpoint reads as everything.
    expect(monthRange('')).toEqual({})
  })

  it('ignores a value that is not YYYY-MM', () => {
    expect(monthRange('2026')).toEqual({})
    expect(monthRange('2026-06-13')).toEqual({})
  })
})
