// Reading a bank statement, without a CSV library.
//
// A statement is a CSV only in the loosest sense: the delimiter depends on the bank's locale,
// amounts come with grouping marks and currency symbols, and the date order is a guess until the
// user tells us. So the parsing here is deliberately tolerant and every ambiguous decision is
// surfaced to the user in the preview instead of being resolved silently.

/** Delimiters worth guessing between. Semicolon-separated exports are common outside en-US. */
const DELIMITERS = [',', ';', '\t', '|']

/**
 * Excel writes a UTF-8 byte-order mark in front of the first header. It is not part of the data,
 * and leaving it there makes the first column name match nothing.
 */
function stripBom(text: string): string {
  return text.charCodeAt(0) === 0xfeff ? text.slice(1) : text
}

/**
 * Picks the delimiter that splits the first line into the most fields. Counting only outside
 * quotes matters: a single `Coffee, large` description would otherwise elect the comma.
 */
export function detectDelimiter(text: string): string {
  const firstLine = stripBom(text).split(/\r?\n/, 1)[0] ?? ''
  let best = ','
  let bestCount = 0
  for (const delimiter of DELIMITERS) {
    let count = 0
    let inQuotes = false
    for (let i = 0; i < firstLine.length; i++) {
      const char = firstLine[i]
      if (char === '"') inQuotes = !inQuotes
      else if (char === delimiter && !inQuotes) count++
    }
    if (count > bestCount) {
      best = delimiter
      bestCount = count
    }
  }
  return best
}

/**
 * Splits CSV text into rows of raw cells. Handles quoted fields, `""` escapes, embedded newlines
 * and both line endings; a trailing blank line is dropped rather than becoming an empty row.
 */
export function parseCsv(text: string, delimiter = detectDelimiter(text)): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let field = ''
  let inQuotes = false
  const source = stripBom(text)

  const endField = () => {
    row.push(field)
    field = ''
  }
  const endRow = () => {
    endField()
    // A line that is entirely empty carries no statement line.
    if (row.length > 1 || row[0] !== '') rows.push(row)
    row = []
  }

  for (let i = 0; i < source.length; i++) {
    const char = source[i]
    if (inQuotes) {
      if (char === '"') {
        if (source[i + 1] === '"') {
          field += '"'
          i++
        } else {
          inQuotes = false
        }
      } else {
        field += char
      }
      continue
    }
    if (char === '"') inQuotes = true
    else if (char === delimiter) endField()
    else if (char === '\n') endRow()
    else if (char !== '\r') field += char
  }
  if (field !== '' || row.length > 0) endRow()
  return rows
}

/**
 * Reads an amount the way a statement writes it. Returns null when there is no number to find, so
 * the caller can report the row rather than importing a zero.
 *
 * The sign is preserved — a statement's minus (or accounting parentheses) is what tells an expense
 * from an income when the file has no separate type column.
 */
export function parseAmount(raw: string): number | null {
  const trimmed = raw.trim()
  if (!trimmed) return null

  const negative = /^\(.*\)$/.test(trimmed) || trimmed.includes('-')
  // Drop currency symbols, letters, spaces and the sign markers now accounted for.
  const digits = trimmed.replace(/[^\d.,]/g, '')
  if (!digits) return null

  const lastDot = digits.lastIndexOf('.')
  const lastComma = digits.lastIndexOf(',')
  let normalised: string
  if (lastDot >= 0 && lastComma >= 0) {
    // Both present: whichever comes last is the decimal separator, the other groups thousands.
    const decimalAt = Math.max(lastDot, lastComma)
    normalised =
      digits.slice(0, decimalAt).replace(/[.,]/g, '') + '.' + digits.slice(decimalAt + 1)
  } else if (lastDot >= 0 || lastComma >= 0) {
    const at = Math.max(lastDot, lastComma)
    const separator = digits[at]
    const after = digits.length - at - 1
    // One separator with exactly three digits behind it, and none in front, is ambiguous:
    // 1.500 is a thousand and a half in en-US and one and a half thousand elsewhere. Grouping is
    // the safer reading — statements print cents as two digits.
    const isGrouping = after === 3 || digits.split(separator).length > 2
    normalised = isGrouping
      ? digits.replace(/[.,]/g, '')
      : digits.slice(0, at) + '.' + digits.slice(at + 1)
  } else {
    normalised = digits
  }

  const value = Number(normalised)
  if (!Number.isFinite(value)) return null
  return negative ? -value : value
}

/** The orders a statement date can be written in. */
export type DateOrder = 'DMY' | 'MDY' | 'YMD'

/**
 * Converts a statement date to the ISO `yyyy-mm-dd` the API expects, or null when the text is not
 * a date. The order cannot be inferred safely (03/04/2026 is two different days), so it is asked
 * for rather than guessed.
 */
export function parseDate(raw: string, order: DateOrder): string | null {
  const parts = raw.trim().match(/\d+/g)
  if (!parts || parts.length < 3) return null

  let year: number
  let month: number
  let day: number
  if (order === 'YMD') [year, month, day] = parts.map(Number)
  else if (order === 'DMY') [day, month, year] = parts.map(Number)
  else [month, day, year] = parts.map(Number)

  if (year < 100) year += 2000
  if (month < 1 || month > 12 || day < 1 || day > 31) return null

  const date = new Date(Date.UTC(year, month - 1, day))
  // Rejects the 31st of a 30-day month, which Date would roll into the next one.
  if (date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null

  return `${year.toString().padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}
