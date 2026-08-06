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
  ImportResult,
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
  budgetId?: string
}): Promise<Transaction> {
  const { data } = await api.post<ApiResponse<Transaction>>('/transactions', body)
  return data.data
}

// A parsed statement. The client does the reading (delimiters, date order, grouping marks are a
// presentation problem); the server still validates every row and decides what is a duplicate.
export async function importTransactions(
  transactions: {
    type: TransactionType
    amount: number
    currency: string
    categoryId: number
    description?: string
    transactionDate: string
    walletId?: number
  }[],
): Promise<ImportResult> {
  const { data } = await api.post<ApiResponse<ImportResult>>('/transactions/import', {
    transactions,
  })
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
// `activeOn` (yyyy-mm-dd) narrows to budgets whose [startDate, endDate] window contains
// that day; omit it to list every budget regardless of period.
export async function listBudgets(activeOn?: string): Promise<Budget[]> {
  const { data } = await api.get<ApiResponse<Budget[]>>('/budgets', {
    params: { page: 1, limit: 100, ...(activeOn ? { activeOn } : {}) },
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

export type DigestMode = 'IMMEDIATE' | 'HOURLY' | 'DAILY'

export interface NotificationPreferences {
  emailEnabled: boolean
  email: string | null
  emailConfigured: boolean
  webhookEnabled: boolean
  webhookUrl: string | null
  // Present on exactly one response: the one that generated it. Every later read is null, so the
  // UI has to show it the moment it arrives or the user has to change the URL to get a new one.
  webhookSecret: string | null
  digestMode: DigestMode
}

export async function notificationPreferences(): Promise<NotificationPreferences> {
  const { data } = await api.get<ApiResponse<NotificationPreferences>>('/notifications/preferences')
  return data.data
}

// No address is sent: the server takes it from the JWT, so a caller cannot redirect another
// account's alerts to a mailbox they own.
export async function setEmailAlerts(emailEnabled: boolean): Promise<NotificationPreferences> {
  const { data } = await api.put<ApiResponse<NotificationPreferences>>(
    '/notifications/preferences',
    { emailEnabled },
  )
  return data.data
}

// url: null clears the webhook and its secret. The server rejects anything that is not a public
// https address (HTTP 400, INVALID_WEBHOOK_URL) — it will be POSTing from inside the private network.
export async function setWebhook(
  url: string | null,
  enabled: boolean,
): Promise<NotificationPreferences> {
  const { data } = await api.put<ApiResponse<NotificationPreferences>>(
    '/notifications/preferences/webhook',
    { url, enabled },
  )
  return data.data
}

export async function setDigestMode(digestMode: DigestMode): Promise<NotificationPreferences> {
  const { data } = await api.put<ApiResponse<NotificationPreferences>>(
    '/notifications/preferences/digest',
    { digestMode },
  )
  return data.data
}

// ---- Web push (notification-service). The subscription belongs to this browser, not to the
// account: signing in elsewhere does not carry it over, and each browser subscribes once.
export async function pushConfig(): Promise<{ enabled: boolean; publicKey: string }> {
  const { data } = await api.get<ApiResponse<{ enabled: boolean; publicKey: string }>>(
    '/push/public-key',
  )
  return data.data
}

export async function subscribeToPush(body: {
  endpoint: string
  p256dh: string
  auth: string
}): Promise<void> {
  await api.post('/push/subscriptions', body)
}

export async function unsubscribeFromPush(endpoint: string): Promise<void> {
  // A body on DELETE: the endpoint is a long URL and would be awkward (and logged) as a query
  // parameter. axios needs it under `data`.
  await api.delete('/push/subscriptions', { data: { endpoint } })
}

// ---- LuckyMe games (transaction-service; a round settles to a real net transaction) ----
// The server spins, settles and moves the money: the client sends only which pockets each chip
// covers, never an outcome and never a bet type — so it cannot pick the winner or its own payout.

export interface GameBan {
  tier: string
  debt: number
  bannedUntil: string
  secondsRemaining: number
}

export interface GameStatus {
  walletId: number
  currency: string
  balance: number
  maxStake: number
  canPlay: boolean
  ban: GameBan | null
}

export interface SettledBet {
  type: string
  pockets: string[]
  amount: number
  won: boolean
  payout: number
}

export interface SpinResult {
  result: string
  colour: 'red' | 'black' | 'green'
  pocketIndex: number
  bets: SettledBet[]
  staked: number
  returned: number
  net: number
  balance: number
  currency: string
  canPlay: boolean
  ban: GameBan | null
}

export async function rouletteStatus(walletId?: number): Promise<GameStatus> {
  const { data } = await api.get<ApiResponse<GameStatus>>('/game/roulette/status', {
    params: walletId ? { walletId } : {},
  })
  return data.data
}

export async function rouletteSpin(
  walletId: number,
  bets: { pockets: string[]; amount: number }[],
): Promise<SpinResult> {
  const { data } = await api.post<ApiResponse<SpinResult>>('/game/roulette/spin', {
    walletId,
    bets,
  })
  return data.data
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
