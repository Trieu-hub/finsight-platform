import type { Category } from '../api/types'

export function money(amount: number, currency = 'USD') {
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
  } catch {
    return `${amount} ${currency}`
  }
}

// Localize a category by id, falling back to the given (backend/English) name when there is
// no translation for the current language. `t` is the i18n translator; EN has no `cat.*` keys
// so it always returns the fallback, VI overrides with `cat.<id>`.
export function catLabel(id: number, fallback: string, t: (key: string) => string): string {
  const key = `cat.${id}`
  const s = t(key)
  return s === key ? fallback : s
}

export function categoryName(
  categories: Category[],
  id: number,
  t?: (key: string) => string,
) {
  const name = categories.find((c) => c.id === id)?.name ?? `#${id}`
  return t ? catLabel(id, name, t) : name
}

// Group a raw digit string into dot-separated thousands for display while typing,
// e.g. "10000000" -> "10.000.000". Non-digits are stripped first.
export function groupThousands(value: string): string {
  const digits = value.replace(/\D/g, '')
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
}

// Money inputs are whole units of digits only. Cap the length to the backend column
// (BigDecimal(19,4) → 15 integer digits) so the UI can never submit a value the API
// would reject, and never overflow JS's safe-integer range. Mirror of the backend's
// @Digits(integer = 15) guard.
export const MAX_MONEY_DIGITS = 15

export function sanitizeMoneyInput(value: string): string {
  return value.replace(/\D/g, '').slice(0, MAX_MONEY_DIGITS)
}
