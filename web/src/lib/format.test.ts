import { describe, it, expect } from 'vitest'
import type { Category } from '../api/types'
import { money, categoryName, groupThousands, sanitizeMoneyInput, MAX_MONEY_DIGITS } from './format'

describe('money', () => {
  it('formats a USD amount with symbol, thousands and 2 decimals', () => {
    expect(money(1234.5)).toBe('$1,234.50')
    expect(money(0)).toBe('$0.00')
  })

  it('honours a non-default currency code', () => {
    expect(money(1000, 'EUR')).toMatch(/1,000/)
  })

  it('falls back to "<amount> <currency>" when the currency code is invalid', () => {
    // Intl.NumberFormat throws on a malformed currency code; money() catches and degrades.
    expect(money(42, 'NOTACODE')).toBe('42 NOTACODE')
  })
})

describe('categoryName', () => {
  const categories: Category[] = [
    { id: 1, name: 'Salary', type: 'INCOME' },
    { id: 4, name: 'Food & Dining', type: 'EXPENSE' },
  ]

  it('returns the matching category name', () => {
    expect(categoryName(categories, 4)).toBe('Food & Dining')
  })

  it('falls back to "#<id>" for an unknown id', () => {
    expect(categoryName(categories, 99)).toBe('#99')
    expect(categoryName([], 1)).toBe('#1')
  })
})

describe('groupThousands', () => {
  it('dot-separates thousands', () => {
    expect(groupThousands('10000000')).toBe('10.000.000')
    expect(groupThousands('999')).toBe('999')
    expect(groupThousands('1000')).toBe('1.000')
  })

  it('strips non-digits before grouping', () => {
    expect(groupThousands('abc1234def')).toBe('1.234')
  })

  it('returns empty for a digit-free string', () => {
    expect(groupThousands('')).toBe('')
    expect(groupThousands('abc')).toBe('')
  })
})

describe('sanitizeMoneyInput', () => {
  it('keeps digits only', () => {
    expect(sanitizeMoneyInput('1,234.56')).toBe('123456')
    expect(sanitizeMoneyInput('$ 10 000')).toBe('10000')
  })

  it('caps the length at MAX_MONEY_DIGITS (mirrors the backend @Digits(integer=15))', () => {
    expect(MAX_MONEY_DIGITS).toBe(15)
    expect(sanitizeMoneyInput('1'.repeat(30))).toHaveLength(15)
  })

  it('returns empty when there are no digits', () => {
    expect(sanitizeMoneyInput('abc.,$')).toBe('')
  })
})
