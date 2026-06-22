import { api } from './client'
import type {
  ApiResponse,
  AuthResponse,
  Budget,
  BudgetPeriod,
  Category,
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
