import { api } from './client'
import type {
  AdminUser,
  AnalyticsOverview,
  ApiResponse,
  AuthResponse,
  Budget,
  BudgetPeriod,
  Category,
  CategorySlice,
  MonthlySummary,
  Notification,
  SpendForecast,
  Transaction,
  TransactionType,
} from './types'

// ---- Auth (public) ----
export async function login(email: string, password: string) {
  const { data } = await api.post<AuthResponse>('/auth/login', { email, password })
  return data
}

export async function register(username: string, email: string, password: string) {
  const { data } = await api.post<AuthResponse>('/auth/register', {
    username,
    email,
    password,
  })
  return data
}

// ---- Admin (ROLE_ADMIN only; backend returns the entity directly, no envelope) ----
export async function listUsers(): Promise<AdminUser[]> {
  const { data } = await api.get<AdminUser[]>('/auth/admin/users')
  return data
}

export async function updateUserRole(id: number, role: string): Promise<AdminUser> {
  const { data } = await api.patch<AdminUser>(`/auth/admin/users/${id}/role`, { role })
  return data
}

export async function updateUserStatus(id: number, enabled: boolean): Promise<AdminUser> {
  const { data } = await api.patch<AdminUser>(`/auth/admin/users/${id}/status`, { enabled })
  return data
}

export async function deleteUser(id: number): Promise<void> {
  await api.delete(`/auth/admin/users/${id}`)
}

// ---- Transactions ----
export async function listTransactions(): Promise<Transaction[]> {
  const { data } = await api.get<ApiResponse<Transaction[]>>('/transactions', {
    params: { page: 1, limit: 100 },
  })
  return data.data
}

export async function createTransaction(body: {
  type: TransactionType
  amount: number
  currency: string
  categoryId: number
  description?: string
  transactionDate: string
}): Promise<Transaction> {
  const { data } = await api.post<ApiResponse<Transaction>>('/transactions', body)
  return data.data
}

// The gateway does not route /api/v1/categories (categories live inside
// transaction-service but only /api/v1/transactions is proxied). Categories are
// static seed data (V2__seed_categories.sql), so we use the known seed list.
// Proper fix later: add a `/api/v1/categories` route to the gateway.
const SEED_CATEGORIES: Category[] = [
  { id: 1, name: 'Salary', type: 'INCOME' },
  { id: 2, name: 'Investment', type: 'INCOME' },
  { id: 3, name: 'Refund', type: 'INCOME' },
  { id: 4, name: 'Food & Dining', type: 'EXPENSE' },
  { id: 5, name: 'Transport', type: 'EXPENSE' },
  { id: 6, name: 'Housing', type: 'EXPENSE' },
  { id: 7, name: 'Utilities', type: 'EXPENSE' },
  { id: 8, name: 'Entertainment', type: 'EXPENSE' },
  { id: 9, name: 'Healthcare', type: 'EXPENSE' },
  { id: 10, name: 'Other', type: 'EXPENSE' },
]

export async function listCategories(): Promise<Category[]> {
  return Promise.resolve(SEED_CATEGORIES)
}

// ---- Budgets ----
export async function listBudgets(): Promise<Budget[]> {
  const { data } = await api.get<ApiResponse<Budget[]>>('/budgets', {
    params: { page: 1, limit: 100 },
  })
  return data.data
}

export async function createBudget(body: {
  name?: string
  categoryId: number
  periodType: BudgetPeriod
  startDate: string
  endDate: string
  limitAmount: number
  currency: string
}): Promise<Budget> {
  const { data } = await api.post<ApiResponse<Budget>>('/budgets', body)
  return data.data
}

// ---- Notifications (in-app; produced by notification-service) ----
export async function listNotifications(unreadOnly = false): Promise<Notification[]> {
  const { data } = await api.get<ApiResponse<Notification[]>>('/notifications', {
    params: { unreadOnly, page: 1, limit: 50 },
  })
  return data.data
}

export async function unreadNotificationCount(): Promise<number> {
  const { data } = await api.get<ApiResponse<{ count: number }>>('/notifications/unread-count')
  return data.data.count
}

export async function markNotificationRead(id: string): Promise<void> {
  await api.patch(`/notifications/${id}/read`)
}

export async function markAllNotificationsRead(): Promise<void> {
  await api.patch('/notifications/read-all')
}

// ---- Analytics (analytics-service; rollup read model built from TransactionCreated) ----
// year/month default to the current month server-side; currency is optional (the user's
// dominant currency for the period is used when omitted).
type MonthParams = { year?: number; month?: number; currency?: string }

export async function analyticsOverview(params: MonthParams = {}): Promise<AnalyticsOverview> {
  const { data } = await api.get<ApiResponse<AnalyticsOverview>>('/analytics/overview', { params })
  return data.data
}

export async function analyticsCategories(
  params: { from?: string; to?: string; currency?: string } = {},
): Promise<CategorySlice[]> {
  const { data } = await api.get<ApiResponse<CategorySlice[]>>('/analytics/categories', { params })
  return data.data
}

export async function analyticsForecast(params: MonthParams = {}): Promise<SpendForecast> {
  const { data } = await api.get<ApiResponse<SpendForecast>>('/analytics/forecast', { params })
  return data.data
}

export async function analyticsSummary(params: MonthParams = {}): Promise<MonthlySummary> {
  const { data } = await api.get<ApiResponse<MonthlySummary>>('/analytics/summary', { params })
  return data.data
}
