import { useEffect, useState, type ReactNode } from 'react'
import { listBudgets, listCategories, listTransactions } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, Category, Transaction } from '../api/types'
import { categoryName, money } from '../lib/format'

// Bar colours for the category breakdown, picked to read on a dark background.
const BAR_COLORS = ['#10b981', '#14b8a6', '#06b6d4', '#f59e0b', '#f43f5e', '#a78bfa', '#64748b']

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
  // Savings rate is the headline metric of a savings app: share of income kept.
  const savingsRate = income > 0 ? (balance / income) * 100 : 0
  // Format dashboard totals in whatever currency the user's transactions use.
  const currency = transactions[0]?.currency ?? 'USD'
  const recent = [...transactions]
    .sort((a, b) => b.transactionDate.localeCompare(a.transactionDate))
    .slice(0, 5)

  // Running balance over time (income +, expense -), one point per day — for the trend chart.
  const dailyNet = new Map<string, number>()
  for (const t of transactions) {
    const delta = (t.type === 'INCOME' ? 1 : -1) * Number(t.amount)
    dailyNet.set(t.transactionDate, (dailyNet.get(t.transactionDate) ?? 0) + delta)
  }
  let running = 0
  const series = [...dailyNet.keys()]
    .sort()
    .map((date) => {
      running += dailyNet.get(date) ?? 0
      return { date, value: running }
    })

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

  if (loading) return <DashboardSkeleton />
  if (error) return <p className="text-red-400">{error}</p>

  return (
    <div className="space-y-8">
      {/* Hero: net balance is the "hero number" with room to breathe; income/expense support it. */}
      <section className="rounded-3xl border border-neutral-800 bg-gradient-to-br from-emerald-500/15 via-neutral-900 to-neutral-900 p-7">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium uppercase tracking-wider text-neutral-400">
            Net balance
          </span>
          <SavingsPill rate={savingsRate} hasIncome={income > 0} />
        </div>
        <div
          className={`mt-3 text-4xl font-bold tracking-tight sm:text-5xl ${
            balance >= 0 ? 'text-neutral-50' : 'text-rose-400'
          }`}
        >
          {money(balance, currency)}
        </div>
        <div className="mt-6 flex flex-wrap gap-x-10 gap-y-4">
          <MiniStat label="Income" value={money(income, currency)} dot="bg-emerald-400" />
          <MiniStat label="Expense" value={money(expense, currency)} dot="bg-rose-400" />
        </div>
      </section>

      <TrendChart series={series} currency={currency} />

      <CategoryColumns byCategory={byCategory} categories={categories} currency={currency} />

      <div className="grid gap-6 md:grid-cols-2">
        <section>
          <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
            Recent transactions
          </h2>
          {recent.length === 0 ? (
            <EmptyCard>No transactions yet.</EmptyCard>
          ) : (
            <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-900">
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
            <EmptyCard>No budgets yet.</EmptyCard>
          ) : (
            <div className="space-y-3">
              {budgets.slice(0, 5).map((b) => (
                <BudgetBar key={b.id} budget={b} categories={categories} />
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function TrendChart({
  series,
  currency,
}: {
  series: { date: string; value: number }[]
  currency: string
}) {
  if (series.length === 0) {
    return (
      <section>
        <SectionTitle>Balance trend</SectionTitle>
        <EmptyCard>Add transactions to see your balance trend.</EmptyCard>
      </section>
    )
  }

  const W = 720
  const H = 220
  const padY = 24
  const padX = 10
  const GUTTER = 'pl-16' // room for the HTML y-axis labels

  const n = series.length
  const values = series.map((s) => s.value)
  let min = Math.min(0, ...values)
  let max = Math.max(0, ...values)
  if (min === max) max = min + 1 // avoid divide-by-zero on a flat line

  const x = (i: number) => (n === 1 ? W / 2 : padX + (i / (n - 1)) * (W - 2 * padX))
  const y = (v: number) => padY + (1 - (v - min) / (max - min)) * (H - 2 * padY)

  const linePath = series.map((s, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(s.value)}`).join(' ')
  const areaPath =
    `M${x(0)},${H - padY} ` +
    series.map((s, i) => `L${x(i)},${y(s.value)}`).join(' ') +
    ` L${x(n - 1)},${H - padY} Z`
  const yZero = y(0)
  const last = series[n - 1]
  const first = series[0]
  const delta = last.value - first.value

  // Y-axis tick fractions (top→bottom); label value + position align to the SVG gridlines.
  const ticks = [0, 0.25, 0.5, 0.75, 1]
  const tickTop = (f: number) => ((padY + f * (H - 2 * padY)) / H) * 100
  const tickVal = (f: number) => max - f * (max - min)

  return (
    <section>
      <div className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-400">
              Balance trend
            </h2>
            <div
              className={`mt-1 text-2xl font-bold tracking-tight ${
                last.value >= 0 ? 'text-neutral-50' : 'text-rose-400'
              }`}
            >
              {money(last.value, currency)}
            </div>
          </div>
          <div className="text-right">
            <span
              className={`inline-flex items-center gap-1 rounded-full border px-2 py-1 text-xs font-semibold ${
                delta >= 0
                  ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                  : 'border-rose-500/30 bg-rose-500/10 text-rose-300'
              }`}
            >
              {delta >= 0 ? '▲' : '▼'} {money(Math.abs(delta), currency)}
            </span>
            <div className="mt-1.5 text-[11px] text-neutral-500">
              {first.date} → {last.date}
            </div>
          </div>
        </div>

        <div className={`relative mt-5 h-56 ${GUTTER}`}>
          {/* Y-axis labels in HTML (the SVG is stretched, so SVG text would distort). */}
          {ticks.map((f) => (
            <span
              key={f}
              className="absolute left-0 w-14 -translate-y-1/2 pr-2 text-right text-[10px] text-neutral-600"
              style={{ top: `${tickTop(f)}%` }}
            >
              {money(tickVal(f), currency)}
            </span>
          ))}
          <svg
            viewBox={`0 0 ${W} ${H}`}
            preserveAspectRatio="none"
            className="h-full w-full"
            aria-label="Balance over time"
          >
            <defs>
              <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor="#10b981" stopOpacity="0.35" />
                <stop offset="100%" stopColor="#10b981" stopOpacity="0" />
              </linearGradient>
            </defs>
            {ticks.map((f) => (
              <line
                key={f}
                x1="0"
                x2={W}
                y1={padY + f * (H - 2 * padY)}
                y2={padY + f * (H - 2 * padY)}
                stroke="#1f1f1f"
                strokeWidth="1"
                vectorEffect="non-scaling-stroke"
              />
            ))}
            {/* zero reference */}
            <line
              x1="0"
              x2={W}
              y1={yZero}
              y2={yZero}
              stroke="#3f3f46"
              strokeWidth="1"
              strokeDasharray="4 4"
              vectorEffect="non-scaling-stroke"
            />
            <path d={areaPath} fill="url(#trendFill)" />
            <path
              d={linePath}
              fill="none"
              stroke="#10b981"
              strokeWidth="2"
              strokeLinejoin="round"
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
            />
            <circle cx={x(n - 1)} cy={y(last.value)} r="3.5" fill="#10b981" />
          </svg>
        </div>
      </div>
    </section>
  )
}

function SavingsPill({ rate, hasIncome }: { rate: number; hasIncome: boolean }) {
  if (!hasIncome) {
    return (
      <span className="rounded-full border border-neutral-700 px-2.5 py-1 text-xs font-medium text-neutral-400">
        Savings rate —
      </span>
    )
  }
  const positive = rate >= 0
  return (
    <span
      className={`flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold ${
        positive
          ? 'border border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
          : 'border border-rose-500/30 bg-rose-500/10 text-rose-300'
      }`}
    >
      {positive ? '▲' : '▼'} {Math.abs(rate).toFixed(0)}% saved
    </span>
  )
}

function MiniStat({ label, value, dot }: { label: string; value: string; dot: string }) {
  return (
    <div>
      <div className="flex items-center gap-1.5 text-xs text-neutral-400">
        <span className={`h-2 w-2 rounded-full ${dot}`} />
        {label}
      </div>
      <div className="mt-1 text-xl font-semibold text-neutral-100">{value}</div>
    </div>
  )
}

// Vertical column chart of expense-by-category, drawn in the same visual language as the
// balance-trend chart (dark card, y-axis gridlines + labels, gradient fills). The detailed
// breakdown list lives on the Analytics page; this is the at-a-glance shape of spending.
function CategoryColumns({
  byCategory,
  categories,
  currency,
}: {
  byCategory: { categoryId: number; total: number }[]
  categories: Category[]
  currency: string
}) {
  if (byCategory.length === 0) {
    return (
      <section>
        <SectionTitle>Spending by category</SectionTitle>
        <EmptyCard>Add an expense to see the breakdown.</EmptyCard>
      </section>
    )
  }

  const cols = byCategory.slice(0, 8)
  const max = Math.max(...cols.map((c) => c.total), 1)
  const ticks = [0, 0.25, 0.5, 0.75, 1]

  return (
    <section>
      <SectionTitle>Spending by category</SectionTitle>
      <div className="rounded-2xl border border-neutral-800 bg-neutral-900 p-5">
        <div className="relative h-56 pl-16">
          {/* Y-axis gridlines + labels (top = max, bottom = 0). */}
          {ticks.map((f) => (
            <div
              key={f}
              className="absolute inset-x-0 flex items-center"
              style={{ top: `${f * 100}%` }}
            >
              <span className="w-16 -translate-y-1/2 pr-2 text-right text-[10px] text-neutral-600">
                {money(max * (1 - f), currency)}
              </span>
              <div className="h-px flex-1 bg-neutral-800/70" />
            </div>
          ))}

          {/* Columns sit on top of the gridlines, anchored to the baseline. */}
          <div className="absolute inset-0 flex items-end gap-2 pl-16">
            {cols.map((c, i) => {
              const pct = (c.total / max) * 100
              const color = BAR_COLORS[i % BAR_COLORS.length]
              return (
                <div
                  key={c.categoryId}
                  className="flex h-full flex-1 items-end justify-center"
                  title={`${categoryName(categories, c.categoryId)}: ${money(c.total, currency)}`}
                >
                  <div
                    className="w-full max-w-[44px] rounded-t-md transition-all hover:brightness-110"
                    style={{
                      height: `${Math.max(pct, 1)}%`,
                      background: `linear-gradient(to top, ${color}, ${color}40)`,
                    }}
                  />
                </div>
              )
            })}
          </div>
        </div>

        {/* X-axis category labels, aligned under each column. */}
        <div className="mt-2 flex gap-2 pl-16">
          {cols.map((c) => (
            <div
              key={c.categoryId}
              className="min-w-0 flex-1 truncate text-center text-[11px] text-neutral-500"
            >
              {categoryName(categories, c.categoryId)}
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
      {children}
    </h2>
  )
}

function EmptyCard({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-dashed border-neutral-800 bg-neutral-900/50 p-8 text-center text-sm text-neutral-500">
      {children}
    </div>
  )
}

function BudgetBar({ budget: b, categories }: { budget: Budget; categories: Category[] }) {
  const pct = b.limitAmount > 0 ? Math.min(100, (b.spentAmount / b.limitAmount) * 100) : 0
  const over = b.spentAmount > b.limitAmount
  return (
    <div className="rounded-2xl border border-neutral-800 bg-neutral-900 p-4">
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

function DashboardSkeleton() {
  return (
    <div className="animate-pulse space-y-8">
      <div className="h-44 rounded-3xl border border-neutral-800 bg-neutral-900" />
      <div className="h-60 rounded-2xl border border-neutral-800 bg-neutral-900" />
      <div className="grid gap-6 md:grid-cols-2">
        <div className="h-56 rounded-2xl border border-neutral-800 bg-neutral-900" />
        <div className="h-56 rounded-2xl border border-neutral-800 bg-neutral-900" />
      </div>
    </div>
  )
}
