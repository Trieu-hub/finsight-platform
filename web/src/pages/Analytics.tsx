import { useEffect, useState, type ReactNode } from 'react'
import {
  analyticsCategories,
  analyticsForecast,
  analyticsOverview,
  analyticsSummary,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import type {
  AnalyticsOverview,
  CategorySlice,
  MonthlySummary,
  SpendForecast,
} from '../api/types'
import { money } from '../lib/format'

const BAR_COLORS = ['#10b981', '#14b8a6', '#06b6d4', '#f59e0b', '#f43f5e', '#a78bfa', '#64748b']

export default function Analytics() {
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null)
  const [categories, setCategories] = useState<CategorySlice[]>([])
  const [forecast, setForecast] = useState<SpendForecast | null>(null)
  const [summary, setSummary] = useState<MonthlySummary | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    ;(async () => {
      try {
        const [ov, cats, fc, sm] = await Promise.all([
          analyticsOverview(),
          analyticsCategories(),
          analyticsForecast(),
          analyticsSummary(),
        ])
        setOverview(ov)
        setCategories(cats)
        setForecast(fc)
        setSummary(sm)
      } catch (err) {
        setError(errorMessage(err))
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  if (loading) return <AnalyticsSkeleton />

  if (error) {
    return (
      <div className="rounded-xl border border-rose-900/50 bg-rose-950/30 p-4 text-sm text-rose-300">
        {error}
      </div>
    )
  }

  const currency = overview?.currency ?? 'USD'
  const expenseSlices = categories.filter((c) => c.type === 'EXPENSE').slice(0, 7)
  const savingsDelta = overview ? overview.savingsRate - overview.prevSavingsRate : 0

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold text-neutral-100">Analytics</h1>
        <p className="mt-1 text-sm text-neutral-400">
          {overview ? monthLabel(overview.yearMonth) : 'This month'} · built from your transaction history
        </p>
      </header>

      {/* AI / rule-based monthly summary */}
      {summary && (
        <section className="rounded-2xl border border-emerald-900/40 bg-gradient-to-br from-emerald-950/40 to-neutral-900 p-5">
          <div className="mb-2 flex items-center gap-2">
            <span className="text-sm font-medium text-emerald-300">Monthly summary</span>
            <span
              className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                summary.aiGenerated
                  ? 'bg-emerald-500/15 text-emerald-300'
                  : 'bg-neutral-700/50 text-neutral-300'
              }`}
            >
              {summary.aiGenerated ? 'AI' : 'Rule-based'}
            </span>
          </div>
          <p className="text-[15px] leading-relaxed text-neutral-100">{summary.summary}</p>
        </section>
      )}

      {/* Headline stats */}
      {overview && (
        <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="Savings rate">
            <div className="text-3xl font-semibold text-neutral-100">{pct(overview.savingsRate)}</div>
            <p className="mt-1 text-xs text-neutral-400">
              {savingsDelta >= 0 ? (
                <span className="text-emerald-400">▲ {pct(Math.abs(savingsDelta))}</span>
              ) : (
                <span className="text-rose-400">▼ {pct(Math.abs(savingsDelta))}</span>
              )}{' '}
              vs last month
            </p>
          </StatCard>

          <StatCard label="Income">
            <div className="text-2xl font-semibold text-emerald-400">{money(overview.income, currency)}</div>
            <div className="mt-1">
              <DeltaChip value={overview.incomeChangePct} goodWhenUp />
            </div>
          </StatCard>

          <StatCard label="Expense">
            <div className="text-2xl font-semibold text-rose-400">{money(overview.expense, currency)}</div>
            <div className="mt-1">
              <DeltaChip value={overview.expenseChangePct} goodWhenUp={false} />
            </div>
          </StatCard>

          <StatCard label="Net">
            <div
              className={`text-2xl font-semibold ${
                overview.net >= 0 ? 'text-neutral-100' : 'text-rose-400'
              }`}
            >
              {money(overview.net, currency)}
            </div>
            <p className="mt-1 text-xs text-neutral-500">income − expense</p>
          </StatCard>
        </section>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Spend forecast */}
        {forecast && (
          <section className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
            <h2 className="text-sm font-medium text-neutral-300">Spend forecast</h2>
            <p className="mt-3 text-3xl font-semibold text-neutral-100">
              {money(forecast.projectedExpense, currency)}
            </p>
            <p className="mt-1 text-xs text-neutral-400">
              projected by month-end at the current pace
            </p>

            <div className="mt-4">
              <div className="mb-1 flex justify-between text-xs text-neutral-400">
                <span>Day {forecast.dayOfMonth} of {forecast.daysInMonth}</span>
                <span>{money(forecast.expenseToDate, currency)} so far</span>
              </div>
              <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-800">
                <div
                  className="h-full rounded-full bg-emerald-500"
                  style={{
                    width: `${Math.min(100, (forecast.dayOfMonth / forecast.daysInMonth) * 100)}%`,
                  }}
                />
              </div>
              <p className="mt-3 text-xs text-neutral-400">
                Averaging <span className="text-neutral-200">{money(forecast.dailyAverage, currency)}</span> / day
              </p>
            </div>
          </section>
        )}

        {/* Top movers */}
        {overview && (
          <section className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
            <h2 className="text-sm font-medium text-neutral-300">Top movers vs last month</h2>
            {overview.topMovers.length === 0 ? (
              <p className="mt-4 text-sm text-neutral-500">No category activity yet.</p>
            ) : (
              <ul className="mt-3 space-y-2">
                {overview.topMovers.map((m) => (
                  <li
                    key={m.categoryId}
                    className="flex items-center justify-between rounded-lg bg-neutral-950/50 px-3 py-2"
                  >
                    <span className="text-sm text-neutral-200">{m.categoryName}</span>
                    <div className="flex items-center gap-3">
                      <span className="text-sm text-neutral-100">{money(m.amount, currency)}</span>
                      <DeltaChip value={m.changePct} goodWhenUp={false} />
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </div>

      {/* Category breakdown */}
      <section className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
        <h2 className="text-sm font-medium text-neutral-300">Spending by category</h2>
        {expenseSlices.length === 0 ? (
          <p className="mt-4 text-sm text-neutral-500">No spending recorded for this period.</p>
        ) : (
          <ul className="mt-4 space-y-3">
            {expenseSlices.map((c, i) => (
              <li key={`${c.categoryId}-${c.type}`}>
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span className="text-neutral-200">{c.categoryName}</span>
                  <span className="text-neutral-400">
                    {money(c.total, currency)} · {pct(c.share)}
                  </span>
                </div>
                <div className="h-2 w-full overflow-hidden rounded-full bg-neutral-800">
                  <div
                    className="h-full rounded-full"
                    style={{
                      width: `${Math.min(100, c.share)}%`,
                      backgroundColor: BAR_COLORS[i % BAR_COLORS.length],
                    }}
                  />
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function StatCard({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-neutral-800 bg-neutral-900 p-4">
      <p className="text-xs font-medium uppercase tracking-wide text-neutral-500">{label}</p>
      <div className="mt-2">{children}</div>
    </div>
  )
}

function DeltaChip({ value, goodWhenUp }: { value: number | null; goodWhenUp: boolean }) {
  if (value === null || value === undefined) {
    return <span className="text-xs text-neutral-500">new</span>
  }
  const up = value >= 0
  const good = up === goodWhenUp
  const color = good ? 'text-emerald-400' : 'text-rose-400'
  return (
    <span className={`text-xs font-medium ${color}`}>
      {up ? '▲' : '▼'} {Math.abs(value).toFixed(1)}%
    </span>
  )
}

function monthLabel(ym: string) {
  const [y, m] = ym.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleString('en-US', { month: 'long', year: 'numeric' })
}

function pct(v: number) {
  return `${v.toFixed(1)}%`
}

function AnalyticsSkeleton() {
  return (
    <div className="space-y-6">
      <div className="h-8 w-40 animate-pulse rounded bg-neutral-800" />
      <div className="h-24 animate-pulse rounded-2xl bg-neutral-900" />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-24 animate-pulse rounded-2xl bg-neutral-900" />
        ))}
      </div>
      <div className="h-48 animate-pulse rounded-2xl bg-neutral-900" />
    </div>
  )
}
