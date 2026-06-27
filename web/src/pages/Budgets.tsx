import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { createBudget, listBudgets, listCategories } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, BudgetPeriod, Category } from '../api/types'
import { categoryName, groupThousands, money } from '../lib/format'

const firstOfMonth = () => new Date().toISOString().slice(0, 8) + '01'
const lastOfMonth = () => {
  const d = new Date()
  return new Date(d.getFullYear(), d.getMonth() + 1, 0).toISOString().slice(0, 10)
}
const CURRENCIES = ['VND', 'USD'] as const

const fieldBase =
  'rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2 text-neutral-100 placeholder-neutral-500 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/30'
const inputClass = `w-full ${fieldBase}`

// Field label above each input (no helper text).
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="block text-sm font-medium text-neutral-300">{label}</label>
      {children}
    </div>
  )
}

export default function Budgets() {
  const [budgets, setBudgets] = useState<Budget[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [periodType, setPeriodType] = useState<BudgetPeriod>('MONTHLY')
  // `limitAmount` holds raw digits; rendered grouped (10.000.000).
  const [limitAmount, setLimitAmount] = useState('')
  const [currency, setCurrency] = useState<string>('VND')
  const [startDate, setStartDate] = useState(firstOfMonth())
  const [endDate, setEndDate] = useState(lastOfMonth())
  const [submitting, setSubmitting] = useState(false)

  async function load() {
    try {
      const [bs, cats] = await Promise.all([listBudgets(), listCategories()])
      setBudgets(bs)
      setCategories(cats)
      // Budgets cap spending, so only EXPENSE categories make sense.
      const firstExpense = cats.find((c) => c.type === 'EXPENSE')
      if (firstExpense && !categoryId) setCategoryId(String(firstExpense.id))
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
    const value = Number(limitAmount)
    if (!limitAmount || value <= 0) {
      setError('Enter a limit greater than 0.')
      return
    }
    setSubmitting(true)
    try {
      await createBudget({
        name: name || undefined,
        categoryId: Number(categoryId),
        periodType,
        startDate,
        endDate,
        limitAmount: value,
        currency,
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
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          New budget
        </h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <Field label="Name">
            <input
              type="text"
              placeholder="Optional"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={inputClass}
            />
          </Field>

          <Field label="Category">
            <select
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              className={inputClass}
            >
              {categories
                .filter((c) => c.type === 'EXPENSE')
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </Field>

          <Field label="Period">
            <select
              value={periodType}
              onChange={(e) => setPeriodType(e.target.value as BudgetPeriod)}
              className={inputClass}
            >
              <option value="MONTHLY">Monthly</option>
              <option value="WEEKLY">Weekly</option>
              <option value="YEARLY">Yearly</option>
              <option value="CUSTOM">Custom</option>
            </select>
          </Field>

          <Field label="Limit amount">
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                placeholder="0"
                value={groupThousands(limitAmount)}
                onChange={(e) => setLimitAmount(e.target.value.replace(/\D/g, ''))}
                required
                className={`${fieldBase} min-w-0 flex-1`}
              />
              <select
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                className={`${fieldBase} w-20 shrink-0`}
                aria-label="Currency"
              >
                {CURRENCIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
          </Field>

          <Field label="Start">
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
              className={inputClass}
            />
          </Field>

          <Field label="End">
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              required
              className={inputClass}
            />
          </Field>

          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Add budget'}
          </button>
        </form>
      </section>

      <section className="md:col-span-2">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          Budgets
        </h2>
        {error && <p className="mb-3 text-sm text-red-400">{error}</p>}
        {loading ? (
          <p className="text-neutral-500">Loading…</p>
        ) : budgets.length === 0 ? (
          <p className="text-neutral-500">No budgets yet.</p>
        ) : (
          <div className="space-y-3">
            {budgets.map((b) => {
              const pct = b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
              const over = b.spentAmount > b.limitAmount
              return (
                <div key={b.id} className="rounded-2xl border border-neutral-800 bg-neutral-900 p-4 transition hover:border-neutral-700">
                  <div className="mb-2 flex items-center justify-between">
                    <span className="font-medium text-neutral-200">
                      {b.name || categoryName(categories, b.categoryId)}
                    </span>
                    <span className="text-sm text-neutral-400">
                      {money(b.spentAmount, b.currency)} / {money(b.limitAmount, b.currency)}
                    </span>
                  </div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-800">
                    <div
                      className={`h-full rounded-full transition-all ${over ? 'bg-rose-500' : 'bg-emerald-500'}`}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                  <div className="mt-1.5 text-xs text-neutral-500">
                    {over && <span className="mr-2 font-medium text-rose-400">Over budget</span>}
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
