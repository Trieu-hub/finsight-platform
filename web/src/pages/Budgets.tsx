import { useEffect, useState, type FormEvent } from 'react'
import { createBudget, listBudgets, listCategories } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, BudgetPeriod, Category } from '../api/types'
import { categoryName, money } from '../lib/format'

const firstOfMonth = () => new Date().toISOString().slice(0, 8) + '01'
const lastOfMonth = () => {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth() + 1, 0).toISOString().slice(0, 10)
}

export default function Budgets() {
  const [budgets, setBudgets] = useState<Budget[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [periodType, setPeriodType] = useState<BudgetPeriod>('MONTHLY')
  const [limitAmount, setLimitAmount] = useState('')
  const [startDate, setStartDate] = useState(firstOfMonth())
  const [endDate, setEndDate] = useState(lastOfMonth())
  const [submitting, setSubmitting] = useState(false)

  async function load() {
    try {
      const [bs, cats] = await Promise.all([listBudgets(), listCategories()])
      setBudgets(bs)
      setCategories(cats)
      if (cats.length && !categoryId) setCategoryId(String(cats[0].id))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setSubmitting(true)
    try {
      await createBudget({
        name: name || undefined,
        categoryId: Number(categoryId),
        periodType,
        startDate,
        endDate,
        limitAmount: Number(limitAmount),
        currency: 'USD',
      })
      setName('')
      setLimitAmount('')
      await load()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="grid gap-6 md:grid-cols-3">
      <section className="md:col-span-1">
        <h2 className="mb-3 text-lg font-semibold">New budget</h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-3 rounded-xl border border-slate-200 bg-white p-4"
        >
          <input
            type="text"
            placeholder="Name (optional)"
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          />
          <select
            value={categoryId}
            onChange={(e) => setCategoryId(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          >
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.type})
              </option>
            ))}
          </select>
          <select
            value={periodType}
            onChange={(e) => setPeriodType(e.target.value as BudgetPeriod)}
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          >
            <option value="MONTHLY">Monthly</option>
            <option value="WEEKLY">Weekly</option>
            <option value="YEARLY">Yearly</option>
            <option value="CUSTOM">Custom</option>
          </select>
          <input
            type="number"
            placeholder="Limit amount"
            value={limitAmount}
            onChange={(e) => setLimitAmount(e.target.value)}
            min="0.01"
            step="0.01"
            required
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          />
          <label className="block text-sm text-slate-500">
            Start
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
              className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2"
            />
          </label>
          <label className="block text-sm text-slate-500">
            End
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              required
              className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2"
            />
          </label>
          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-md bg-indigo-600 py-2 font-medium text-white hover:bg-indigo-700 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Add budget'}
          </button>
        </form>
      </section>

      <section className="md:col-span-2">
        <h2 className="mb-3 text-lg font-semibold">Budgets</h2>
        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
        {loading ? (
          <p className="text-slate-500">Loading…</p>
        ) : budgets.length === 0 ? (
          <p className="text-slate-500">No budgets yet.</p>
        ) : (
          <div className="space-y-3">
            {budgets.map((b) => {
              const pct = b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
              const over = b.spentAmount > b.limitAmount
              return (
                <div key={b.id} className="rounded-xl border border-slate-200 bg-white p-4">
                  <div className="mb-2 flex items-center justify-between">
                    <span className="font-medium">
                      {b.name || categoryName(categories, b.categoryId)}
                    </span>
                    <span className="text-sm text-slate-500">
                      {money(b.spentAmount, b.currency)} / {money(b.limitAmount, b.currency)}
                    </span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-slate-100">
                    <div
                      className={`h-full rounded-full ${over ? 'bg-red-500' : 'bg-indigo-500'}`}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                  <div className="mt-1 text-xs text-slate-400">
                    {b.periodType} · {b.startDate} → {b.endDate}
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}
