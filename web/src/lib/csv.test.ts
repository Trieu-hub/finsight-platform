import { describe, expect, it } from 'vitest'

import { detectDelimiter, parseAmount, parseCsv, parseDate } from './csv'

describe('detectDelimiter', () => {
  it('picks the comma for a comma-separated header', () => {
    expect(detectDelimiter('date,amount,description')).toBe(',')
  })

  it('picks the semicolon used by non-en-US exports', () => {
    expect(detectDelimiter('date;amount;description')).toBe(';')
  })

  it('ignores delimiters inside a quoted field', () => {
    // One real semicolon, three commas — but all the commas sit inside quotes.
    expect(detectDelimiter('"Coffee, large, hot";2026-01-02')).toBe(';')
  })
})

describe('parseCsv', () => {
  it('splits rows and cells', () => {
    expect(parseCsv('a,b\n1,2')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ])
  })

  it('keeps a delimiter that is inside quotes', () => {
    expect(parseCsv('"Coffee, large",5')).toEqual([['Coffee, large', '5']])
  })

  it('unescapes a doubled quote', () => {
    expect(parseCsv('"He said ""hi""",5')).toEqual([['He said "hi"', '5']])
  })

  it('handles CRLF line endings and a trailing newline', () => {
    expect(parseCsv('a,b\r\n1,2\r\n')).toEqual([
      ['a', 'b'],
      ['1', '2'],
    ])
  })

  it('strips a UTF-8 BOM so the first header is not corrupted', () => {
    expect(parseCsv('﻿date,amount')[0][0]).toBe('date')
  })

  it('keeps a newline that is inside a quoted field', () => {
    expect(parseCsv('"line one\nline two",5')).toEqual([['line one\nline two', '5']])
  })
})

describe('parseAmount', () => {
  it('reads a plain number', () => {
    expect(parseAmount('42')).toBe(42)
  })

  it('reads en-US grouping with decimals', () => {
    expect(parseAmount('1,234,567.89')).toBe(1234567.89)
  })

  it('reads the other convention, where the comma is the decimal mark', () => {
    expect(parseAmount('1.234.567,89')).toBe(1234567.89)
  })

  it('treats a lone separator with three digits behind it as grouping', () => {
    // The VND reading. Cents are printed with two digits, so 1.500 is fifteen hundred.
    expect(parseAmount('1.500')).toBe(1500)
  })

  it('treats a lone separator with two digits behind it as a decimal mark', () => {
    expect(parseAmount('15,50')).toBe(15.5)
  })

  it('keeps a minus sign, which is how an expense announces itself', () => {
    expect(parseAmount('-250000')).toBe(-250000)
  })

  it('reads accounting parentheses as negative', () => {
    expect(parseAmount('(1,200.00)')).toBe(-1200)
  })

  it('ignores a currency symbol or code around the number', () => {
    expect(parseAmount('$1,200.00')).toBe(1200)
    expect(parseAmount('250.000 VND')).toBe(250000)
  })

  it('returns null when there is no number to read', () => {
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('  ')).toBeNull()
    expect(parseAmount('n/a')).toBeNull()
  })
})

describe('parseDate', () => {
  it('reads day-first dates', () => {
    expect(parseDate('03/04/2026', 'DMY')).toBe('2026-04-03')
  })

  it('reads month-first dates', () => {
    expect(parseDate('03/04/2026', 'MDY')).toBe('2026-03-04')
  })

  it('reads ISO dates', () => {
    expect(parseDate('2026-04-03', 'YMD')).toBe('2026-04-03')
  })

  it('accepts dots and dashes as separators', () => {
    expect(parseDate('03.04.2026', 'DMY')).toBe('2026-04-03')
    expect(parseDate('03-04-2026', 'DMY')).toBe('2026-04-03')
  })

  it('expands a two-digit year into this century', () => {
    expect(parseDate('03/04/26', 'DMY')).toBe('2026-04-03')
  })

  it('ignores a time that follows the date', () => {
    expect(parseDate('03/04/2026 14:30', 'DMY')).toBe('2026-04-03')
  })

  it('rejects a day that does not exist in that month', () => {
    expect(parseDate('31/04/2026', 'DMY')).toBeNull()
  })

  it('rejects text that is not a date', () => {
    expect(parseDate('opening balance', 'DMY')).toBeNull()
    expect(parseDate('04/2026', 'DMY')).toBeNull()
  })
})
