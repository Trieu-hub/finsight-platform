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
  Wallet,
  WalletKind,
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
  walletId?: number
  toWalletId?: number
}): Promise<Transaction> {
  const { data } = await api.post<ApiResponse<Transaction>>('/transactions', body)
  return data.data
}

// Categories live in transaction-service and are now proxied by the gateway
// (`/api/v1/categories` → transaction-service). They are global reference data
// seeded via Flyway (V2__seed_categories.sql).
export async function listCategories(): Promise<Category[]> {
  const { data } = await api.get<ApiResponse<Category[]>>('/categories')
  return data.data
}

// ---- Wallets (accounts with a running balance, maintained by transaction writes) ----
export async function listWallets(): Promise<Wallet[]> {
  const { data } = await api.get<ApiResponse<Wallet[]>>('/wallets')
  return data.data
}

export async function createWallet(body: {
  name: string
  type: WalletKind
  currency: string
  initialBalance?: number
}): Promise<Wallet> {
  const { data } = await api.post<ApiResponse<Wallet>>('/wallets', body)
  return data.data
}

export async function updateWallet(
  id: number,
  body: { name?: string; type?: WalletKind },
): Promise<Wallet> {
  const { data } = await api.put<ApiResponse<Wallet>>(`/wallets/${id}`, body)
  return data.data
}

export async function deleteWallet(id: number): Promise<void> {
  await api.delete(`/wallets/${id}`)
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
