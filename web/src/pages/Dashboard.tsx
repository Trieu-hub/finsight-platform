import { useEffect, useState } from 'react'
import { listBudgets, listCategories, listTransactions } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, Category, Transaction } from '../api/types'
import { categoryName, money } from '../lib/format'

export default function Dashboard() {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [budgets, setBudgets] = useState<Budget[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    ;(async () => {
      try {
        const [tx, bs, cats] = await Promise.all([
          listTransactions(),
          listBudgets(),
          listCategories(),
        ])
        setTransactions(tx)
        setBudgets(bs)
        setCategories(cats)
      } catch (err) {
        setError(errorMessage(err))
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  const income = transactions
    .filter((t) => t.type === 'INCOME')
    .reduce((s, t) => s + Number(t.amount), 0)
  const expense = transactions
    .filter((t) => t.type === 'EXPENSE')
    .reduce((s, t) => s + Number(t.amount), 0)
  const balance = income - expense
  const recent = [...transactions]
    .sort((a, b) => b.transactionDate.localeCompare(a.transactionDate))
    .slice(0, 5)

  // Expense totals grouped by category, biggest first — for the breakdown bars.
  const byCategory = Object.values(
    transactions
      .filter((t) => t.type === 'EXPENSE')
      .reduce<Record<number, { categoryId: number; total: number }>>((acc, t) => {
        acc[t.categoryId] ??= { categoryId: t.categoryId, total: 0 }
        acc[t.categoryId].total += Number(t.amount)
        return acc
      }, {}),
  ).sort((a, b) => b.total - a.total)
  const maxCat = byCategory.length ? byCategory[0].total : 0

  if (loading) return <p className="text-neutral-500">Loading…</p>
  if (error) return <p className="text-red-400">{error}</p>

  return (
    <div className="space-y-8">
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Income" value={money(income)} accent="from-emerald-500/20" valueClass="text-emerald-400" />
        <Stat label="Expense" value={money(expense)} accent="from-rose-500/20" valueClass="text-rose-400" />
        <Stat
          label="Balance"
          value={money(balance)}
          accent="from-emerald-500/20"
          valueClass={balance >= 0 ? 'text-neutral-100' : 'text-rose-400'}
        />
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
            Recent transactions
          </h2>
          {recent.length === 0 ? (
            <p className="text-neutral-500">No transactions yet.</p>
          ) : (
            <div className="overflow-hidden rounded-xl border border-neutral-800 bg-neutral-900">
              {recent.map((t) => (
                <div
                  key={t.id}
                  className="flex items-center justify-between border-b border-neutral-800 px-4 py-3 last:border-0"
                >
                  <div>
                    <div className="text-sm font-medium text-neutral-200">
                      {categoryName(categories, t.categoryId)}
                    </div>
                    <div className="text-xs text-neutral-500">{t.transactionDate}</div>
                  </div>
                  <span
                    className={`text-sm font-semibold ${
                      t.type === 'INCOME' ? 'text-emerald-400' : 'text-rose-400'
                    }`}
                  >
                    {t.type === 'INCOME' ? '+' : '-'}
                    {money(t.amount, t.currency)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>

        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
            Budgets
          </h2>
          {budgets.length === 0 ? (
            <p className="text-neutral-500">No budgets yet.</p>
          ) : (
            <div className="space-y-3">
              {budgets.slice(0, 5).map((b) => (
                <BudgetBar key={b.id} budget={b} categories={categories} />
              ))}
            </div>
          )}
        </section>
      </div>

      <section>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          Spending by category
        </h2>
        {byCategory.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-neutral-800 bg-neutral-900/50 p-8 text-center text-sm text-neutral-500">
            No expenses yet — add a transaction to see the breakdown.
          </div>
        ) : (
          <div className="space-y-3 rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
            {byCategory.map((c) => (
              <div key={c.categoryId}>
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span className="text-neutral-300">{categoryName(categories, c.categoryId)}</span>
                  <span className="text-neutral-400">{money(c.total)}</span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-800">
                  <div
                    className="h-full rounded-full bg-gradient-to-r from-emerald-500 to-teal-400"
                    style={{ width: `${maxCat > 0 ? (c.total / maxCat) * 100 : 0}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}

function Stat({
  label,
  value,
  valueClass = '',
  accent,
}: {
  label: string
  value: string
  valueClass?: string
  accent: string
}) {
  return (
    <div
      className={`rounded-2xl border border-neutral-800 bg-gradient-to-br ${accent} to-neutral-900 p-5 transition hover:border-neutral-700`}
    >
      <div className="text-xs font-medium uppercase tracking-wide text-neutral-400">{label}</div>
      <div className={`mt-2 text-2xl font-bold ${valueClass}`}>{value}</div>
    </div>
  )
}

function BudgetBar({ budget: b, categories }: { budget: Budget; categories: Category[] }) {
  const pct = b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
  const over = b.spentAmount > b.limitAmount
  return (
    <div className="rounded-xl border border-neutral-800 bg-neutral-900 p-4">
      <div className="mb-2 flex items-center justify-between text-sm">
        <span className="font-medium text-neutral-200">
          {b.name || categoryName(categories, b.categoryId)}
        </span>
        <span className="text-neutral-400">
          {money(b.spentAmount, b.currency)} / {money(b.limitAmount, b.currency)}
        </span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-800">
        <div
          className={`h-full rounded-full transition-all ${over ? 'bg-rose-500' : 'bg-emerald-500'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  )
}
