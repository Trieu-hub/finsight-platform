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

// In-app notification materialized by notification-service from a RiskDetected event.
// Severity mirrors the upstream risk severity (HIGH/MEDIUM/LOW).
export type NotificationSeverity = 'HIGH' | 'MEDIUM' | 'LOW'

export interface Notification {
  id: string
  type: string
  severity: NotificationSeverity
  title: string
  message: string
  read: boolean
  createdAt: string
  readAt?: string
}

// ---- Analytics (analytics-service: rollup read model) ----
export interface CategoryMover {
  categoryId: number
  categoryName: string
  amount: number
  prevAmount: number
  changePct: number | null
}

export interface AnalyticsOverview {
  yearMonth: string
  currency: string
  income: number
  expense: number
  net: number
  savingsRate: number
  prevIncome: number
  prevExpense: number
  prevNet: number
  prevSavingsRate: number
  incomeChangePct: number | null
  expenseChangePct: number | null
  topMovers: CategoryMover[]
}

export interface CategorySlice {
  categoryId: number
  categoryName: string
  type: TransactionType
  total: number
  txnCount: number
  share: number
}

export interface SpendForecast {
  yearMonth: string
  currency: string
  expenseToDate: number
  dayOfMonth: number
  daysInMonth: number
  projectedExpense: number
  dailyAverage: number
}

export interface MonthlySummary {
  yearMonth: string
  currency: string
  summary: string
  aiGenerated: boolean
}
