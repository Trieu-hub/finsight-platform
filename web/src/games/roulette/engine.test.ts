import { describe, it, expect } from 'vitest'
import {
  WHEEL,
  POCKET_COUNT,
  colourOf,
  PAYOUT,
  classify,
  keyOf,
  probability,
  COLUMNS,
  DOZENS,
  EVEN_MONEY,
} from './engine'

const NUMS = Array.from({ length: 36 }, (_, i) => String(i + 1))

describe('wheel', () => {
  it('has 38 distinct pockets (American: 0, 00, 1..36)', () => {
    expect(POCKET_COUNT).toBe(38)
    expect(new Set(WHEEL).size).toBe(38)
  })
})

describe('colourOf', () => {
  it('0 and 00 are green', () => {
    expect(colourOf('0')).toBe('green')
    expect(colourOf('00')).toBe('green')
  })

  it('matches the standard red/black assignment', () => {
    expect(colourOf('1')).toBe('red')
    expect(colourOf('2')).toBe('black')
    expect(colourOf('36')).toBe('red')
  })

  it('splits 1..36 into exactly 18 red and 18 black', () => {
    expect(NUMS.filter((n) => colourOf(n) === 'red')).toHaveLength(18)
    expect(NUMS.filter((n) => colourOf(n) === 'black')).toHaveLength(18)
  })
})

describe('payout invariant (36 - n)/n', () => {
  // The single rule governing the whole inside table: a bet covering n pockets pays (36 - n)/n,
  // giving every bet the same -5.263% edge — except the basket. Mirrors the backend RouletteTest.
  it('every inside bet type except basket pays (36 - n)/n', () => {
    const samples: Record<string, string[]> = {
      straight: ['7'],
      split: ['1', '2'],
      street: ['1', '2', '3'],
      trio: ['0', '1', '2'],
      corner: ['1', '2', '4', '5'],
      sixline: ['1', '2', '3', '4', '5', '6'],
    }
    for (const [type, cover] of Object.entries(samples)) {
      const bet = classify(cover)
      expect(bet, `classify(${cover}) should be a legal ${type}`).not.toBeNull()
      expect(bet!.type).toBe(type)
      expect(PAYOUT[bet!.type]).toBe((36 - cover.length) / cover.length)
    }
  })

  it('the basket is the deliberate outlier: 6:1 on 5 pockets, not 6.2:1', () => {
    const basket = classify(['0', '00', '1', '2', '3'])
    expect(basket?.type).toBe('basket')
    expect(PAYOUT.basket).toBe(6)
    expect((36 - 5) / 5).toBe(6.2) // the 0.2 shortfall is the extra house edge on the basket
  })

  it('every even-money bet pays 1:1', () => {
    for (const t of ['red', 'black', 'odd', 'even', 'low', 'high'] as const) {
      expect(PAYOUT[t]).toBe(1)
    }
  })
})

describe('classify', () => {
  it('rejects an illegal chip position (non-adjacent numbers)', () => {
    expect(classify(['1', '5'])).toBeNull()
  })

  it('is order-independent — keyOf canonicalises the cover set', () => {
    expect(classify(['2', '1'])?.type).toBe('split')
    expect(keyOf(['3', '1', '2'])).toBe(keyOf(['1', '2', '3']))
  })

  it('an empty selection is not a bet', () => {
    expect(classify([])).toBeNull()
  })
})

describe('outside bets', () => {
  it('each column and dozen covers 12 numbers; each even-money bet covers 18', () => {
    expect(COLUMNS).toHaveLength(3)
    expect(DOZENS).toHaveLength(3)
    for (const c of COLUMNS) expect(c.covers).toHaveLength(12)
    for (const d of DOZENS) expect(d.covers).toHaveLength(12)
    for (const e of EVEN_MONEY) expect(e.covers).toHaveLength(18)
  })
})

describe('probability', () => {
  it('is n / 38', () => {
    expect(probability(1)).toBeCloseTo(1 / 38)
    expect(probability(18)).toBeCloseTo(18 / 38)
  })
})
