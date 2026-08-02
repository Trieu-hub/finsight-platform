import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import {
  createTransaction,
  listBudgets,
  listCategories,
  listTransactions,
  listWallets,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, Category, Transaction, TransactionType, Wallet } from '../api/types'
import { catLabel, categoryName, groupThousands, money, sanitizeMoneyInput } from '../lib/format'
import { useI18n } from '../i18n'

const today = () => new Date().toISOString().slice(0, 10)
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

// History is shown newest first: sort by transaction date, tie-broken by creation time.
const byNewest = (a: Transaction, b: Transaction) =>
  b.transactionDate.localeCompare(a.transactionDate) ||
  (b.createdAt ?? '').localeCompare(a.createdAt ?? '')

// A budget is eligible for an expense when it is on the chosen category, in the same currency,
// and its window contains the transaction date — the only budgets the user may charge it to.
function budgetEligible(
  b: Budget,
  tx: { categoryId: number; currency: string; transactionDate: string },
): boolean {
  return (
    b.categoryId === tx.categoryId &&
    b.currency === tx.currency &&
    tx.transactionDate >= b.startDate &&
    tx.transactionDate <= b.endDate
  )
}

export default function Transactions() {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [budgets, setBudgets] = useState<Budget[]>([])
  // Set when a just-added expense pushes a matching budget over its limit — drives the popup.
  const [overBudget, setOverBudget] = useState<
    { name: string; spent: number; limit: number; currency: string } | null
  >(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  // History period filter: 'YYYY-MM' shows just that month, '' shows all. Defaults to this month.
  const [month, setMonth] = useState(today().slice(0, 7))

  // form state — `amount` holds raw digits; it is rendered grouped (10.000.000).
  const [type, setType] = useState<TransactionType>('EXPENSE')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState<string>('VND')
  const [categoryId, setCategoryId] = useState('')
  const [budgetId, setBudgetId] = useState('') // chosen budget for an EXPENSE; '' = none
  const [walletId, setWalletId] = useState('') // source (or single) wallet; '' = none
  const [toWalletId, setToWalletId] = useState('') // TRANSFER destination
  const [description, setDescription] = useState('')
  const [date, setDate] = useState(today())
  const [submitting, setSubmitting] = useState(false)
  const { t } = useI18n()

  const isTransfer = type === 'TRANSFER'
  const transferCat = useMemo(() => categories.find((c) => c.type === 'TRANSFER'), [categories])
  const sourceWallet = useMemo(
    () => wallets.find((w) => String(w.id) === walletId),
    [wallets, walletId],
  )
  // A wallet fixes the transaction currency (no FX): lock it to the chosen wallet's currency.
  const lockedCurrency = sourceWallet?.currency
  const effectiveCurrency = lockedCurrency ?? currency

  // The category this transaction carries: the user's pick while it is valid for the chosen type,
  // else the first category of that type. Derived like the budget below rather than stored, so it
  // also covers the type changing *before* the categories arrive — the stored version was left
  // empty then, and because an empty value matches no <option> the browser still displayed the
  // first one: a form that looked filled in but posted categoryId 0.
  const effectiveCategoryId = useMemo(() => {
    if (categories.some((c) => String(c.id) === categoryId && c.type === type)) return categoryId
    const firstOfType = categories.find((c) => c.type === type)
    return firstOfType ? String(firstOfType.id) : ''
  }, [categories, categoryId, type])

  // Budgets the user may charge this expense to (same category + currency, covering the date).
  const matchingBudgets = useMemo(() => {
    if (type !== 'EXPENSE' || !effectiveCategoryId) return []
    const catId = Number(effectiveCategoryId)
    return budgets.filter((b) =>
      budgetEligible(b, { categoryId: catId, currency: effectiveCurrency, transactionDate: date }),
    )
  }, [budgets, type, effectiveCategoryId, effectiveCurrency, date])

  // The budget this expense is charged to: the user's pick if still eligible, else the first
  // eligible one (so an expense always carries a budget when one exists). Derived, not stored,
  // so it self-corrects as category/currency/date change — no state to keep in sync.
  const effectiveBudgetId =
    type === 'EXPENSE'
      ? matchingBudgets.some((b) => b.id === budgetId)
        ? budgetId
        : (matchingBudgets[0]?.id ?? '')
      : ''

  // Distinct months present in the loaded history (plus the current one), newest first —
  // populates the period filter. '' means "all months".
  const months = useMemo(() => {
    const set = new Set(transactions.map((tx) => tx.transactionDate.slice(0, 7)))
    set.add(today().slice(0, 7))
    return [...set].sort((a, b) => b.localeCompare(a))
  }, [transactions])

  // Rows actually shown: filtered to the chosen month (if any), newest first.
  const visibleTransactions = useMemo(() => {
    const list = month
      ? transactions.filter((tx) => tx.transactionDate.slice(0, 7) === month)
      : transactions
    return [...list].sort(byNewest)
  }, [transactions, month])

  async function load() {
    try {
      const [tx, cats, ws, bs] = await Promise.all([
        listTransactions(),
        listCategories(),
        listWallets(),
        listBudgets(),
      ])
      setTransactions(tx)
      setCategories(cats)
      setWallets(ws)
      setBudgets(bs)
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

  // Auto-dismiss the over-budget popup after a few seconds.
  useEffect(() => {
    if (!overBudget) return
    const id = setTimeout(() => setOverBudget(null), 7000)
    return () => clearTimeout(id)
  }, [overBudget])


  function onTypeChange(next: TransactionType) {
    setType(next)
    // The category follows from the type in the effect above; only the transfer-specific field
    // is this handler's business.
    if (next !== 'TRANSFER') setToWalletId('')
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const value = Number(amount)
    if (!amount || value <= 0) {
      setError(t('tx.errAmount'))
      return
    }

    try {
      if (isTransfer) {
        if (!walletId || !toWalletId) {
          setError(t('tx.errWallets'))
          return
        }
        if (walletId === toWalletId) {
          setError(t('tx.errSameWallet'))
          return
        }
        if (!transferCat) {
          setError(t('tx.errTransferCat'))
          return
        }
        setSubmitting(true)
        await createTransaction({
          type: 'TRANSFER',
          amount: value,
          currency: sourceWallet!.currency,
          categoryId: transferCat.id,
          description: description || undefined,
          transactionDate: date,
          walletId: Number(walletId),
          toWalletId: Number(toWalletId),
        })
      } else {
        // An expense must be charged to a budget (the user picks which). Block if the category
        // has no eligible budget in this period, or none is chosen.
        if (type === 'EXPENSE') {
          if (matchingBudgets.length === 0) {
            setError(t('tx.errNoBudget'))
            return
          }
          if (!effectiveBudgetId) {
            setError(t('tx.errBudgetRequired'))
            return
          }
        }
        setSubmitting(true)
        const created = await createTransaction({
          type,
          amount: value,
          currency: effectiveCurrency,
          categoryId: Number(effectiveCategoryId),
          description: description || undefined,
          transactionDate: date,
          walletId: sourceWallet ? sourceWallet.id : undefined,
          budgetId: type === 'EXPENSE' ? effectiveBudgetId : undefined,
        })
        // Warn immediately if this expense pushes the chosen budget over its limit. Computed
        // client-side (this budget's prior spend + this amount) so the popup is instant, ahead of
        // the asynchronous budget tally that arrives over Kafka.
        if (type === 'EXPENSE') {
          const b = budgets.find((x) => x.id === effectiveBudgetId)
          if (b) {
            const prior = transactions
              .filter((tx) => tx.type === 'EXPENSE' && tx.budgetId === b.id)
              .reduce((s, tx) => s + Number(tx.amount), 0)
            const spent = prior + Number(created.amount)
            if (spent > b.limitAmount) {
              setOverBudget({
                name: b.name || categoryName(categories, b.categoryId, t),
                spent,
                limit: b.limitAmount,
                currency: b.currency,
              })
            }
          }
        }
      }
      setAmount('')
      setDescription('')
      await load()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  // Destination options for a transfer: same currency as the source, excluding the source.
  const destinationWallets = wallets.filter(
    (w) => sourceWallet && w.currency === sourceWallet.currency && w.id !== sourceWallet.id,
  )

  return (
    <>
      {overBudget && (
        <OverBudgetToast data={overBudget} onClose={() => setOverBudget(null)} t={t} />
      )}
      <div className="grid gap-6 md:grid-cols-3">
      {/* Create form */}
      <section data-tour="tx-form" className="md:col-span-1">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          {t('tx.new')}
        </h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <Field label={t('tx.type')}>
            <select
              value={type}
              onChange={(e) => onTypeChange(e.target.value as TransactionType)}
              className={inputClass}
            >
              <option value="EXPENSE">{t('tx.expense')}</option>
              <option value="INCOME">{t('tx.income')}</option>
              <option value="TRANSFER">{t('tx.transfer')}</option>
            </select>
          </Field>

          <Field label={t('tx.amount')}>
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                placeholder="0"
                value={groupThousands(amount)}
                onChange={(e) => setAmount(sanitizeMoneyInput(e.target.value))}
                required
                className={`${fieldBase} min-w-0 flex-1`}
              />
              <select
                value={effectiveCurrency}
                onChange={(e) => setCurrency(e.target.value)}
                disabled={!!lockedCurrency}
                className={`${fieldBase} w-20 shrink-0 disabled:opacity-60`}
                aria-label="Currency"
                title={lockedCurrency ? t('tx.currencyLocked') : undefined}
              >
                {CURRENCIES.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
                {lockedCurrency && !CURRENCIES.includes(lockedCurrency as 'VND' | 'USD') && (
                  <option value={lockedCurrency}>{lockedCurrency}</option>
                )}
              </select>
            </div>
          </Field>

          {isTransfer ? (
            <>
              <Field label={t('tx.fromWallet')}>
                <select
                  value={walletId}
                  onChange={(e) => {
                    setWalletId(e.target.value)
                    setToWalletId('') // destination depends on source currency
                  }}
                  className={inputClass}
                >
                  <option value="">{t('common.select')}</option>
                  {wallets.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {money(w.balance, w.currency)}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label={t('tx.toWallet')}>
                <select
                  value={toWalletId}
                  onChange={(e) => setToWalletId(e.target.value)}
                  disabled={!sourceWallet}
                  className={`${inputClass} disabled:opacity-60`}
                >
                  <option value="">{t('common.select')}</option>
                  {destinationWallets.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {money(w.balance, w.currency)}
                    </option>
                  ))}
                </select>
              </Field>
            </>
          ) : (
            <>
              <Field label={t('tx.category')}>
                <select
                  value={effectiveCategoryId}
                  onChange={(e) => setCategoryId(e.target.value)}
                  className={inputClass}
                >
                  {categories
                    .filter((c) => c.type === type)
                    .map((c) => (
                      <option key={c.id} value={c.id}>
                        {catLabel(c.id, c.name, t)}
                      </option>
                    ))}
                </select>
              </Field>

              {type === 'EXPENSE' && (
                <Field label={t('tx.budget')}>
                  {matchingBudgets.length === 0 ? (
                    <p className="rounded-lg border border-amber-500/30 bg-amber-950/30 px-3 py-2 text-sm text-amber-300">
                      {t('tx.errNoBudget')}
                    </p>
                  ) : (
                    <select
                      value={effectiveBudgetId}
                      onChange={(e) => setBudgetId(e.target.value)}
                      className={inputClass}
                    >
                      {matchingBudgets.map((b) => (
                        <option key={b.id} value={b.id}>
                          {`${b.name || categoryName(categories, b.categoryId, t)} · ${money(
                            b.spentAmount,
                            b.currency,
                          )} / ${money(b.limitAmount, b.currency)}`}
                        </option>
                      ))}
                    </select>
                  )}
                </Field>
              )}

              <Field label={t('tx.wallet')}>
                <select
                  value={walletId}
                  onChange={(e) => setWalletId(e.target.value)}
                  className={inputClass}
                >
                  <option value="">{t('common.none')}</option>
                  {wallets.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {money(w.balance, w.currency)}
                    </option>
                  ))}
                </select>
              </Field>
            </>
          )}

          <Field label={t('tx.description')}>
            <input
              type="text"
              placeholder={t('common.optional')}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className={inputClass}
            />
          </Field>

          <Field label={t('tx.date')}>
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              required
              className={inputClass}
            />
          </Field>

          <button
            type="submit"
            disabled={submitting || (type === 'EXPENSE' && matchingBudgets.length === 0)}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? t('tx.saving') : t('tx.add')}
          </button>
        </form>
      </section>

      {/* List */}
      <section data-tour="tx-list" className="md:col-span-2">
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-400">
            {t('tx.title')}
          </h2>
          {!loading && (
            <select
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              aria-label={t('tx.filterMonth')}
              className="rounded-lg border border-neutral-700 bg-neutral-950/60 px-2 py-1 text-xs text-neutral-300 outline-none transition focus:border-emerald-500 focus:ring-1 focus:ring-emerald-500/30"
            >
              <option value="">{t('tx.allMonths')}</option>
              {months.map((m) => (
                <option key={m} value={m}>
                  {`${m.slice(5)}/${m.slice(0, 4)}`}
                </option>
              ))}
            </select>
          )}
        </div>
        {error && <p className="mb-3 text-sm text-red-400">{error}</p>}
        {loading ? (
          <p className="text-neutral-500">{t('common.loading')}</p>
        ) : visibleTransactions.length === 0 ? (
          <p className="text-neutral-500">{month ? t('tx.noTxMonth') : t('dashboard.noTx')}</p>
        ) : (
          <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-900">
            <table className="w-full text-sm">
              <thead className="bg-neutral-950/40 text-left text-neutral-400">
                <tr>
                  <th className="px-4 py-2.5 font-medium">{t('tx.colDate')}</th>
                  <th className="px-4 py-2.5 font-medium">{t('tx.colCategory')}</th>
                  <th className="px-4 py-2.5 font-medium">{t('tx.colDescription')}</th>
                  <th className="px-4 py-2.5 text-right font-medium">{t('tx.colAmount')}</th>
                </tr>
              </thead>
              <tbody>
                {visibleTransactions.map((tx) => {
                  const xfer = tx.type === 'TRANSFER'
                  const color = xfer
                    ? 'text-sky-400'
                    : tx.type === 'INCOME'
                      ? 'text-emerald-400'
                      : 'text-rose-400'
                  const prefix = xfer ? '⇄ ' : tx.type === 'INCOME' ? '+' : '-'
                  return (
                    <tr key={tx.id} className="border-t border-neutral-800 transition hover:bg-neutral-800/40">
                      <td className="px-4 py-2.5 text-neutral-500">{tx.transactionDate}</td>
                      <td className="px-4 py-2.5 text-neutral-200">{categoryName(categories, tx.categoryId, t)}</td>
                      <td className="px-4 py-2.5 text-neutral-500">{tx.description ?? '—'}</td>
                      <td className={`px-4 py-2.5 text-right font-semibold ${color}`}>
                        {prefix}
                        {money(tx.amount, tx.currency)}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
      </div>
    </>
  )
}

// Popup shown the moment a new expense pushes a matching budget over its limit.
function OverBudgetToast({
  data,
  onClose,
  t,
}: {
  data: { name: string; spent: number; limit: number; currency: string }
  onClose: () => void
  t: (key: string, vars?: Record<string, string | number>) => string
}) {
  return (
    <div className="fixed inset-x-0 top-4 z-50 flex justify-center px-4">
      <div
        role="alert"
        className="flex w-full max-w-md items-start gap-3 rounded-2xl border border-rose-500/40 bg-rose-950/90 px-4 py-3 shadow-2xl shadow-black/50 backdrop-blur"
      >
        <span className="mt-0.5 text-lg">⚠️</span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-rose-200">{t('budget.exceededTitle')}</p>
          <p className="mt-0.5 text-sm text-rose-100/90">
            {t('budget.exceededBody', {
              name: data.name,
              spent: money(data.spent, data.currency),
              limit: money(data.limit, data.currency),
            })}
          </p>
        </div>
        <button
          onClick={onClose}
          aria-label="Close"
          className="shrink-0 rounded-md px-1.5 text-rose-300 transition hover:bg-rose-900/60 hover:text-rose-100"
        >
          ✕
        </button>
      </div>
    </div>
  )
}
