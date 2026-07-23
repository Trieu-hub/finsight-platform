import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { createBudget, listBudgets, listCategories, listWallets } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, BudgetPeriod, Category, Wallet } from '../api/types'
import { catLabel, categoryName, groupThousands, money, sanitizeMoneyInput } from '../lib/format'
import { useI18n } from '../i18n'

// Local (not UTC) yyyy-mm-dd so week/month edges don't slip a day in +07:00.
const toISODate = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

// Default [start, end] for a period, anchored on today. CUSTOM keeps the user's
// own dates (returns null) so switching to it never wipes what they typed.
function rangeFor(period: BudgetPeriod, ref = new Date()): { start: string; end: string } | null {
  const d = new Date(ref.getFullYear(), ref.getMonth(), ref.getDate())
  if (period === 'WEEKLY') {
    const start = new Date(d)
    start.setDate(d.getDate() - ((d.getDay() + 6) % 7)) // back to Monday
    const end = new Date(start)
    end.setDate(start.getDate() + 6) // through Sunday
    return { start: toISODate(start), end: toISODate(end) }
  }
  if (period === 'MONTHLY') {
    return {
      start: toISODate(new Date(d.getFullYear(), d.getMonth(), 1)),
      end: toISODate(new Date(d.getFullYear(), d.getMonth() + 1, 0)),
    }
  }
  if (period === 'YEARLY') {
    return {
      start: toISODate(new Date(d.getFullYear(), 0, 1)),
      end: toISODate(new Date(d.getFullYear(), 11, 31)),
    }
  }
  return null // CUSTOM
}

const MONTHLY_RANGE = rangeFor('MONTHLY')!
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
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const [name, setName] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [periodType, setPeriodType] = useState<BudgetPeriod>('MONTHLY')
  // `limitAmount` holds raw digits; rendered grouped (10.000.000).
  const [limitAmount, setLimitAmount] = useState('')
  const [currency, setCurrency] = useState<string>('VND')
  const [startDate, setStartDate] = useState(MONTHLY_RANGE.start)
  const [endDate, setEndDate] = useState(MONTHLY_RANGE.end)
  const [submitting, setSubmitting] = useState(false)
  const { t } = useI18n()

  async function load() {
    try {
      const [bs, cats, ws] = await Promise.all([listBudgets(), listCategories(), listWallets()])
      setBudgets(bs)
      setCategories(cats)
      setWallets(ws)
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

  // Switching the period snaps the dates to that period (this week / this month /
  // this year). CUSTOM leaves them alone so the user can pick any range by hand.
  function handlePeriodChange(next: BudgetPeriod) {
    setPeriodType(next)
    const r = rangeFor(next)
    if (r) {
      setStartDate(r.start)
      setEndDate(r.end)
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const value = Number(limitAmount)
    if (!limitAmount || value <= 0) {
      setError(t('budget.errLimit'))
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

  // A budget caps real spending, and spending always comes out of a wallet — so a
  // wallet must exist first. Available balance is scoped to the picked currency (no FX).
  const hasWallets = wallets.length > 0
  const availableBalance = wallets
    .filter((w) => w.currency === currency)
    .reduce((sum, w) => sum + w.balance, 0)
  const overBalance = Number(limitAmount) > 0 && Number(limitAmount) > availableBalance

  return (
    <div className="grid gap-6 md:grid-cols-3">
      <section data-tour="budget-form" className="md:col-span-1">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          {t('budget.new')}
        </h2>
        {!loading && !hasWallets ? (
          <div className="space-y-3 rounded-2xl border border-neutral-800 bg-neutral-900 p-5 text-center">
            <p className="font-medium text-neutral-200">{t('budget.needWallet')}</p>
            <p className="text-sm text-neutral-400">{t('budget.needWalletHint')}</p>
            <Link
              to="/wallets"
              className="inline-block rounded-lg bg-emerald-600 px-4 py-2 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500"
            >
              {t('budget.createWallet')}
            </Link>
          </div>
        ) : (
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <Field label={t('budget.name')}>
            <input
              type="text"
              placeholder={t('common.optional')}
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={inputClass}
            />
          </Field>

          <Field label={t('budget.category')}>
            <select
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              className={inputClass}
            >
              {categories
                .filter((c) => c.type === 'EXPENSE')
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {catLabel(c.id, c.name, t)}
                  </option>
                ))}
            </select>
          </Field>

          <Field label={t('budget.period')}>
            <select
              value={periodType}
              onChange={(e) => handlePeriodChange(e.target.value as BudgetPeriod)}
              className={inputClass}
            >
              <option value="MONTHLY">{t('period.MONTHLY')}</option>
              <option value="WEEKLY">{t('period.WEEKLY')}</option>
              <option value="YEARLY">{t('period.YEARLY')}</option>
              <option value="CUSTOM">{t('period.CUSTOM')}</option>
            </select>
          </Field>

          <Field label={t('budget.limit')}>
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                placeholder="0"
                value={groupThousands(limitAmount)}
                onChange={(e) => setLimitAmount(sanitizeMoneyInput(e.target.value))}
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
            {overBalance && (
              <p className="mt-1.5 text-xs text-amber-400">
                {t('budget.overBalance', { balance: money(availableBalance, currency) })}
              </p>
            )}
          </Field>

          <Field label={t('budget.start')}>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              required
              className={inputClass}
            />
          </Field>

          <Field label={t('budget.end')}>
            <input
              type="date"
              value={endDate}
              onChange={(e) => setEndDate(e.target.value)}
              required
              className={inputClass}
            />
          </Field>

          {periodType !== 'CUSTOM' && (
            <p className="-mt-2 text-xs text-neutral-500">{t('budget.dateHint')}</p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? t('budget.saving') : t('budget.add')}
          </button>
        </form>
        )}
      </section>

      <section data-tour="budget-list" className="md:col-span-2">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          {t('budget.title')}
        </h2>
        {error && <p className="mb-3 text-sm text-red-400">{error}</p>}
        {loading ? (
          <p className="text-neutral-500">{t('common.loading')}</p>
        ) : budgets.length === 0 ? (
          <p className="text-neutral-500">{t('dashboard.noBudgets')}</p>
        ) : (
          <div className="space-y-3">
            {budgets.map((b) => {
              const pct = b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
              const over = b.spentAmount > b.limitAmount
              return (
                <div key={b.id} className="rounded-2xl border border-neutral-800 bg-neutral-900 p-4 transition hover:border-neutral-700">
                  <div className="mb-2 flex items-center justify-between">
                    <span className="font-medium text-neutral-200">
                      {b.name || categoryName(categories, b.categoryId, t)}
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
                    {over && <span className="mr-2 font-medium text-rose-400">{t('budget.over')}</span>}
                    {t(`period.${b.periodType}`)} · {b.startDate} → {b.endDate}
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
