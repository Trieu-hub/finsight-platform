import { useEffect, useState, type ReactNode } from 'react'
import {
  analyticsCategories,
  analyticsForecast,
  analyticsOverview,
  analyticsSummary,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import { loadFailure, valueOr } from '../lib/settled'
import type {
  AnalyticsOverview,
  CategorySlice,
  MonthlySummary,
  SpendForecast,
} from '../api/types'
import { catLabel, money } from '../lib/format'
import { useI18n, type Lang } from '../i18n'

const BAR_COLORS = ['#10b981', '#14b8a6', '#06b6d4', '#f59e0b', '#f43f5e', '#a78bfa', '#64748b']

export default function Analytics() {
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null)
  const [categories, setCategories] = useState<CategorySlice[]>([])
  const [forecast, setForecast] = useState<SpendForecast | null>(null)
  const [summary, setSummary] = useState<MonthlySummary | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const { t, lang } = useI18n()

  useEffect(() => {
    ;(async () => {
      // allSettled: offline these are served from cache, and one miss must not blank the page.
      // The summary is the likeliest to be absent — it is not cached at all — and losing the
      // overview with it would be a poor trade.
      const results = await Promise.allSettled([
        analyticsOverview(),
        analyticsCategories(),
        analyticsForecast(),
        analyticsSummary(),
      ])
      const [ov, cats, fc, sm] = results
      setOverview(valueOr(ov, null))
      setCategories(valueOr(cats, []))
      setForecast(valueOr(fc, null))
      setSummary(valueOr(sm, null))
      const failure = loadFailure(results, navigator.onLine)
      if (failure) setError(errorMessage(failure))
      setLoading(false)
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
        <h1 className="text-2xl font-semibold text-neutral-100">{t('an.title')}</h1>
        <p className="mt-1 text-sm text-neutral-400">
          {overview ? monthLabel(overview.yearMonth, lang) : t('an.thisMonth')} · {t('an.subtitle')}
        </p>
      </header>

      {/* AI / rule-based monthly summary */}
      {summary && (
        <section data-tour="analytics-summary" className="rounded-2xl border border-emerald-900/40 bg-gradient-to-br from-emerald-950/40 to-neutral-900 p-5">
          <div className="mb-2 flex items-center gap-2">
            <span className="text-sm font-medium text-emerald-300">{t('an.monthlySummary')}</span>
            <span
              className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                summary.aiGenerated
                  ? 'bg-emerald-500/15 text-emerald-300'
                  : 'bg-neutral-700/50 text-neutral-300'
              }`}
            >
              {summary.aiGenerated ? t('an.ai') : t('an.ruleBased')}
            </span>
          </div>
          <p className="text-[15px] leading-relaxed text-neutral-100">{summary.summary}</p>
        </section>
      )}

      {/* Headline stats */}
      {overview && (
        <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label={t('an.savingsRate')}>
            <div className="text-3xl font-semibold text-neutral-100">{pct(overview.savingsRate)}</div>
            <p className="mt-1 text-xs text-neutral-400">
              {savingsDelta >= 0 ? (
                <span className="text-emerald-400">▲ {pct(Math.abs(savingsDelta))}</span>
              ) : (
                <span className="text-rose-400">▼ {pct(Math.abs(savingsDelta))}</span>
              )}{' '}
              {t('an.vsLastMonth')}
            </p>
          </StatCard>

          <StatCard label={t('an.income')}>
            <div className="text-2xl font-semibold text-emerald-400">{money(overview.income, currency)}</div>
            <div className="mt-1">
              <DeltaChip value={overview.incomeChangePct} goodWhenUp />
            </div>
          </StatCard>

          <StatCard label={t('an.expense')}>
            <div className="text-2xl font-semibold text-rose-400">{money(overview.expense, currency)}</div>
            <div className="mt-1">
              <DeltaChip value={overview.expenseChangePct} goodWhenUp={false} />
            </div>
          </StatCard>

          <StatCard label={t('an.net')}>
            <div
              className={`text-2xl font-semibold ${
                overview.net >= 0 ? 'text-neutral-100' : 'text-rose-400'
              }`}
            >
              {money(overview.net, currency)}
            </div>
            <p className="mt-1 text-xs text-neutral-500">{t('an.netHint')}</p>
          </StatCard>
        </section>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Spend forecast */}
        {forecast && (
          <section data-tour="analytics-forecast" className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
            <div className="flex items-center gap-2">
              <h2 className="text-sm font-medium text-neutral-300">{t('an.forecast')}</h2>
              {/* Which projection answered. Same chip as the summary's AI/rule-based badge:
                  the two numbers are not interchangeable, so the page says which one this is. */}
              <span
                className={`rounded-full px-2 py-0.5 text-[11px] font-medium ${
                  forecast.method === 'MODEL'
                    ? 'bg-emerald-500/15 text-emerald-300'
                    : 'bg-neutral-700/50 text-neutral-300'
                }`}
              >
                {forecast.method === 'MODEL' ? t('an.forecastModel') : t('an.forecastRunRate')}
              </span>
            </div>
            <p className="mt-3 text-3xl font-semibold text-neutral-100">
              {money(forecast.projectedExpense, currency)}
            </p>
            <p className="mt-1 text-xs text-neutral-400">
              {forecast.method === 'MODEL' ? t('an.forecastHintModel') : t('an.forecastHint')}
            </p>
            {/* Only the model carries an error estimate; the run rate has none to show. */}
            {forecast.projectedLow !== null && forecast.projectedHigh !== null && (
              <p className="mt-1 text-xs text-neutral-500">
                {t('an.forecastRange', {
                  low: money(forecast.projectedLow, currency),
                  high: money(forecast.projectedHigh, currency),
                })}
              </p>
            )}

            <div className="mt-4">
              <div className="mb-1 flex justify-between text-xs text-neutral-400">
                <span>{t('an.dayOf', { d: forecast.dayOfMonth, n: forecast.daysInMonth })}</span>
                <span>{t('an.soFar', { amount: money(forecast.expenseToDate, currency) })}</span>
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
                {t('an.averagingPre')}{' '}
                <span className="text-neutral-200">{money(forecast.dailyAverage, currency)}</span>{' '}
                {t('an.averagingPost')}
              </p>
            </div>
          </section>
        )}

        {/* Top movers */}
        {overview && (
          <section className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
            <h2 className="text-sm font-medium text-neutral-300">{t('an.topMovers')}</h2>
            {overview.topMovers.length === 0 ? (
              <p className="mt-4 text-sm text-neutral-500">{t('an.noCategory')}</p>
            ) : (
              <ul className="mt-3 space-y-2">
                {overview.topMovers.map((m) => (
                  <li
                    key={m.categoryId}
                    className="flex items-center justify-between rounded-lg bg-neutral-950/50 px-3 py-2"
                  >
                    <span className="text-sm text-neutral-200">{catLabel(m.categoryId, m.categoryName, t)}</span>
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
        <h2 className="text-sm font-medium text-neutral-300">{t('an.spendingByCategory')}</h2>
        {expenseSlices.length === 0 ? (
          <p className="mt-4 text-sm text-neutral-500">{t('an.noSpending')}</p>
        ) : (
          <ul className="mt-4 space-y-3">
            {expenseSlices.map((c, i) => (
              <li key={`${c.categoryId}-${c.type}`}>
                <div className="mb-1 flex items-center justify-between text-sm">
                  <span className="text-neutral-200">{catLabel(c.categoryId, c.categoryName, t)}</span>
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
  const { t } = useI18n()
  if (value === null || value === undefined) {
    return <span className="text-xs text-neutral-500">{t('an.new')}</span>
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

function monthLabel(ym: string, lang: Lang) {
  const [y, m] = ym.split('-').map(Number)
  return new Date(y, m - 1, 1).toLocaleString(lang === 'vi' ? 'vi-VN' : 'en-US', {
    month: 'long',
    year: 'numeric',
  })
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
