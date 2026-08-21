import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import {
  createTransaction,
  exportTransactionsCsv,
  listBudgets,
  listCategories,
  listTransactions,
  listWallets,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Budget, Category, Transaction, TransactionType, Wallet } from '../api/types'
import { monthRange, saveBlob } from '../lib/download'
import { catLabel, categoryName, groupThousands, money, sanitizeMoneyInput } from '../lib/format'
import { enqueue } from '../lib/outbox'
import { loadFailure, valueOr } from '../lib/settled'
import { useOnline } from '../hooks/useOnline'
import { useOutbox } from '../hooks/useOutbox'
import { useI18n } from '../i18n'

const today = () => new Date().toISOString().slice(0, 10)
const CURRENCIES = ['VND', 'USD'] as const

const fieldBase =
  'rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2 text-neutral-100 placeholder-neutral-500 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/30'
const inputClass = `w-full ${fieldBase}`

// Field label above each input (no helper text). The <label> wraps the control so the two are
// associated without an id, as in Login's Field — a bare <label> next to the input named nothing,
// which left every control here unlabelled for screen readers.
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block space-y-1.5">
      <span className="block text-sm font-medium text-neutral-300">{label}</span>
      {children}
    </label>
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
  // Reactive, for what the form *renders*. The submit path reads `navigator.onLine` directly
  // instead: that is the authoritative value at the moment the decision is made, where a state
  // update that has not flushed yet would be a bug.
  const online = useOnline()
  // Drains anything composed offline as soon as the network is back, and reports what is waiting.
  // `load` is a hoisted function declaration below; reloading once the queue lands is what makes
  // the transaction appear in the list without the user refreshing.
  const outbox = useOutbox(() => void load())
  // History period filter: 'YYYY-MM' shows just that month, '' shows all. Defaults to this month.
  const [month, setMonth] = useState(today().slice(0, 7))
  const [exporting, setExporting] = useState(false)

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

  /**
   * Downloads the chosen period as CSV. The server does the rendering and applies the same
   * period the table is showing — the list on screen is only the first 100 rows, so exporting
   * what is loaded here would quietly hand the user an incomplete file.
   */
  async function handleExport() {
    setExporting(true)
    setError('')
    try {
      const blob = await exportTransactionsCsv(monthRange(month))
      saveBlob(blob, `vernfy-transactions-${month || 'all'}.csv`)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  async function load() {
    // allSettled, not all: offline these come from the service worker's cache, and a single miss
    // — a month whose transactions were never fetched, say — used to reject the whole batch and
    // leave `categories` empty. An empty category list makes the form unusable, so one missing
    // figure took the entire page down with it.
    const results = await Promise.allSettled([
      listTransactions(),
      listCategories(),
      listWallets(),
      listBudgets(),
    ])
    const [tx, cats, ws, bs] = results
    setTransactions(valueOr(tx, []))
    setCategories(valueOr(cats, []))
    setWallets(valueOr(ws, []))
    setBudgets(valueOr(bs, []))
    const failure = loadFailure(results, navigator.onLine)
    setError(failure ? errorMessage(failure) : '')
    setLoading(false)
  }

  useEffect(() => {
    load()
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

  /**
   * Queues a write instead of sending it, when there is no network to send it on.
   *
   * `transactionDate` is already in the payload, so replaying this on Thursday still books it on
   * the day the user picked — the drift that made a write queue a bad idea before this existed.
   *
   * @returns true when it was queued and the caller should stop; false to carry on and POST.
   */
  function queueWhileOffline(payload: Parameters<typeof enqueue>[0]): boolean {
    if (navigator.onLine) return false
    const item = enqueue(payload)
    if (!item) {
      // The queue is full. Say so rather than clearing the form, which would look like a save.
      setError(t('outbox.full'))
      return true
    }
    setAmount('')
    setDescription('')
    outbox.refresh()
    return true
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
        const transfer = {
          type: 'TRANSFER' as const,
          amount: value,
          currency: sourceWallet!.currency,
          categoryId: transferCat.id,
          description: description || undefined,
          transactionDate: date,
          walletId: Number(walletId),
          toWalletId: Number(toWalletId),
        }
        // A transfer queues like anything else. Leaving it out was an oversight, not a decision:
        // moving money between your own wallets is exactly the sort of thing recorded away from
        // a signal.
        if (!queueWhileOffline(transfer)) {
          setSubmitting(true)
          await createTransaction(transfer)
        }
      } else {
        // An expense must be charged to a budget (the user picks which). Block if the category
        // has no eligible budget in this period, or none is chosen.
        //
        // Enforced only while online, and that exception is the whole reason the offline queue is
        // reachable at all: offline, `budgets` holds whatever the cache had — usually nothing —
        // so this guard fired on every expense and returned before the queue was ever consulted.
        // Since EXPENSE is the default type, that made the feature unreachable in exactly the
        // case it exists for. A queued expense may therefore land unattributed; the user can
        // charge it to a budget afterwards, which is a far smaller loss than not capturing it.
        if (type === 'EXPENSE' && navigator.onLine) {
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
        const payload = {
          type,
          amount: value,
          currency: effectiveCurrency,
          categoryId: Number(effectiveCategoryId),
          description: description || undefined,
          transactionDate: date,
          walletId: sourceWallet ? sourceWallet.id : undefined,
          budgetId: type === 'EXPENSE' ? effectiveBudgetId : undefined,
        }

        if (queueWhileOffline(payload)) return

        const created = await createTransaction(payload)
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
      <section data-tour="tx-form" className="min-w-0 md:col-span-1">
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
                  {matchingBudgets.length === 0 && online ? (
                    // Amber stays on the border, the words use the neutral ramp: index.css flips
                    // the neutral scale with the theme but leaves accents alone, so amber ink on
                    // an amber tint is only legible in the dark.
                    <p className="rounded-lg border border-amber-500/50 bg-neutral-900/40 px-3 py-2 text-sm text-neutral-200">
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
            // The budget requirement is lifted while offline — see handleSubmit. Leaving it here
            // made the button unclickable exactly when the offline queue was meant to catch the
            // write, so the feature could not be reached at all: no error, no clue, just a button
            // that does nothing.
            disabled={submitting || (type === 'EXPENSE' && online && matchingBudgets.length === 0)}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? t('tx.saving') : t('tx.add')}
          </button>
        </form>
      </section>

      {/* List */}
      <section data-tour="tx-list" className="min-w-0 md:col-span-2">
        {/* Queued offline. Shown online too: the drain needs a moment, and silence in between
            would read as "my transaction vanished". */}
        {outbox.pending > 0 && (
          <p
            role="status"
            className="mb-3 rounded-lg border border-amber-900/60 bg-amber-950/40 px-3 py-2 text-xs text-amber-200"
          >
            {t('outbox.pending', { n: outbox.pending })}
          </p>
        )}
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-400">
            {t('tx.title')}
          </h2>
          {!loading && (
            <div className="flex items-center gap-2">
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
              <button
                type="button"
                onClick={handleExport}
                disabled={exporting}
                className="rounded-lg border border-neutral-700 px-2 py-1 text-xs text-neutral-300 transition hover:border-emerald-500 hover:text-emerald-400 disabled:opacity-60"
              >
                {exporting ? t('tx.exporting') : t('tx.export')}
              </button>
            </div>
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
