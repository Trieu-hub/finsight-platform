import type { Category } from '../api/types'

export function money(amount: number, currency = 'USD') {
  try {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
  } catch {
    return `${amount} ${currency}`
  }
}

export function categoryName(categories: Category[], id: number) {
  return categories.find((c) => c.id === id)?.name ?? `#${id}`
}

// Group a raw digit string into dot-separated thousands for display while typing,
// e.g. "10000000" -> "10.000.000". Non-digits are stripped first.
export function groupThousands(value: string): string {
  const digits = value.replace(/\D/g, '')
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
}
