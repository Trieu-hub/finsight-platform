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
