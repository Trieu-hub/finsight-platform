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

  if (loading) return <p className="text-slate-500">Loading…</p>
  if (error) return <p className="text-red-600">{error}</p>

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-3">
        <Stat label="Income" value={money(income)} className="text-green-600" />
        <Stat label="Expense" value={money(expense)} className="text-red-600" />
        <Stat
          label="Balance"
          value={money(balance)}
          className={balance >= 0 ? 'text-slate-800' : 'text-red-600'}
        />
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <section>
          <h2 className="mb-3 text-lg font-semibold">Recent transactions</h2>
          {recent.length === 0 ? (
            <p className="text-slate-500">No transactions yet.</p>
          ) : (
            <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
              {recent.map((t) => (
                <div
                  key={t.id}
                  className="flex items-center justify-between border-b border-slate-100 px-4 py-2.5 last:border-0"
                >
                  <div>
                    <div className="text-sm font-medium">
                      {categoryName(categories, t.categoryId)}
                    </div>
                    <div className="text-xs text-slate-400">{t.transactionDate}</div>
                  </div>
                  <span
                    className={`text-sm font-medium ${
                      t.type === 'INCOME' ? 'text-green-600' : 'text-red-600'
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
          <h2 className="mb-3 text-lg font-semibold">Budgets</h2>
          {budgets.length === 0 ? (
            <p className="text-slate-500">No budgets yet.</p>
          ) : (
            <div className="space-y-3">
              {budgets.slice(0, 5).map((b) => {
                const pct =
                  b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
                const over = b.spentAmount > b.limitAmount
                return (
                  <div key={b.id} className="rounded-xl border border-slate-200 bg-white p-4">
                    <div className="mb-2 flex items-center justify-between text-sm">
                      <span className="font-medium">
                        {b.name || categoryName(categories, b.categoryId)}
                      </span>
                      <span className="text-slate-500">
                        {money(b.spentAmount, b.currency)} / {money(b.limitAmount, b.currency)}
                      </span>
                    </div>
                    <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100">
                      <div
                        className={`h-full rounded-full ${over ? 'bg-red-500' : 'bg-indigo-500'}`}
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function Stat({
  label,
  value,
  className = '',
}: {
  label: string
  value: string
  className?: string
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="text-sm text-slate-500">{label}</div>
      <div className={`mt-1 text-2xl font-bold ${className}`}>{value}</div>
    </div>
  )
}
