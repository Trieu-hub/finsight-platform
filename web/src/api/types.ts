// Mirror of the backend wire contracts. Keep field names identical to the Java DTOs.

export type TransactionType = 'INCOME' | 'EXPENSE'
export type BudgetPeriod = 'MONTHLY' | 'WEEKLY' | 'YEARLY' | 'CUSTOM'

// Standard success envelope: { success, data, meta }. meta omitted when null.
export interface ApiResponse<T> {
  success: boolean
  data: T
  meta?: PageMeta
}

export interface PageMeta {
  page: number
  limit: number
  total: number
}

// auth-service returns the token at the TOP level (not wrapped in data).
export interface AuthResponse {
  success: boolean
  message?: string
  accessToken?: string
  refreshToken?: string
}

export interface Transaction {
  id: string
  type: TransactionType
  amount: number
  currency: string
  categoryId: number
  description?: string
  transactionDate: string
  createdAt: string
}

export interface Category {
  id: number
  name: string
  type: TransactionType
}

export interface AdminUser {
  id: number
  username: string
  email: string
  role: string
  enabled: boolean
  createdAt: string
}

export interface Budget {
  id: string
  name?: string
  categoryId: number
  periodType: BudgetPeriod
  startDate: string
  endDate: string
  limitAmount: number
  spentAmount: number
  currency: string
}
