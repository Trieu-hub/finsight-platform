import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import {
  createTransaction,
  listCategories,
  listTransactions,
  listWallets,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Category, Transaction, TransactionType, Wallet } from '../api/types'
import { categoryName, groupThousands, money } from '../lib/format'

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

export default function Transactions() {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  // form state — `amount` holds raw digits; it is rendered grouped (10.000.000).
  const [type, setType] = useState<TransactionType>('EXPENSE')
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState<string>('VND')
  const [categoryId, setCategoryId] = useState('')
  const [walletId, setWalletId] = useState('') // source (or single) wallet; '' = none
  const [toWalletId, setToWalletId] = useState('') // TRANSFER destination
  const [description, setDescription] = useState('')
  const [date, setDate] = useState(today())
  const [submitting, setSubmitting] = useState(false)

  const isTransfer = type === 'TRANSFER'
  const transferCat = useMemo(() => categories.find((c) => c.type === 'TRANSFER'), [categories])
  const sourceWallet = useMemo(
    () => wallets.find((w) => String(w.id) === walletId),
    [wallets, walletId],
  )
  // A wallet fixes the transaction currency (no FX): lock it to the chosen wallet's currency.
  const lockedCurrency = sourceWallet?.currency
  const effectiveCurrency = lockedCurrency ?? currency

  async function load() {
    try {
      const [tx, cats, ws] = await Promise.all([listTransactions(), listCategories(), listWallets()])
      setTransactions(tx)
      setCategories(cats)
      setWallets(ws)
      // Default to the first category that MATCHES the current type (avoids the
      // contradictory "EXPENSE + Salary" default).
      const firstOfType = cats.find((c) => c.type === type)
      if (firstOfType && !categoryId) setCategoryId(String(firstOfType.id))
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

  function onTypeChange(next: TransactionType) {
    setType(next)
    if (next === 'TRANSFER') {
      setCategoryId(transferCat ? String(transferCat.id) : '')
    } else {
      // Reset the category to one valid for the new type (e.g. never EXPENSE + Salary).
      const firstOfType = categories.find((c) => c.type === next)
      setCategoryId(firstOfType ? String(firstOfType.id) : '')
      setToWalletId('')
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    const value = Number(amount)
    if (!amount || value <= 0) {
      setError('Enter an amount greater than 0.')
      return
    }

    try {
      if (isTransfer) {
        if (!walletId || !toWalletId) {
          setError('Choose both a source and a destination wallet.')
          return
        }
        if (walletId === toWalletId) {
          setError('Source and destination wallets must be different.')
          return
        }
        if (!transferCat) {
          setError('Transfer category is unavailable.')
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
        setSubmitting(true)
        await createTransaction({
          type,
          amount: value,
          currency: effectiveCurrency,
          categoryId: Number(categoryId),
          description: description || undefined,
          transactionDate: date,
          walletId: sourceWallet ? sourceWallet.id : undefined,
        })
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
    <div className="grid gap-6 md:grid-cols-3">
      {/* Create form */}
      <section className="md:col-span-1">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          New transaction
        </h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <Field label="Type">
            <select
              value={type}
              onChange={(e) => onTypeChange(e.target.value as TransactionType)}
              className={inputClass}
            >
              <option value="EXPENSE">Expense</option>
              <option value="INCOME">Income</option>
              <option value="TRANSFER">Transfer</option>
            </select>
          </Field>

          <Field label="Amount">
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                placeholder="0"
                value={groupThousands(amount)}
                onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))}
                required
                className={`${fieldBase} min-w-0 flex-1`}
              />
              <select
                value={effectiveCurrency}
                onChange={(e) => setCurrency(e.target.value)}
                disabled={!!lockedCurrency}
                className={`${fieldBase} w-20 shrink-0 disabled:opacity-60`}
                aria-label="Currency"
                title={lockedCurrency ? 'Currency is set by the selected wallet' : undefined}
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
              <Field label="From wallet">
                <select
                  value={walletId}
                  onChange={(e) => {
                    setWalletId(e.target.value)
                    setToWalletId('') // destination depends on source currency
                  }}
                  className={inputClass}
                >
                  <option value="">Select…</option>
                  {wallets.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {money(w.balance, w.currency)}
                    </option>
                  ))}
                </select>
              </Field>

              <Field label="To wallet">
                <select
                  value={toWalletId}
                  onChange={(e) => setToWalletId(e.target.value)}
                  disabled={!sourceWallet}
                  className={`${inputClass} disabled:opacity-60`}
                >
                  <option value="">Select…</option>
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
              <Field label="Category">
                <select
                  value={categoryId}
                  onChange={(e) => setCategoryId(e.target.value)}
                  className={inputClass}
                >
                  {categories
                    .filter((c) => c.type === type)
                    .map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.name}
                      </option>
                    ))}
                </select>
              </Field>

              <Field label="Wallet">
                <select
                  value={walletId}
                  onChange={(e) => setWalletId(e.target.value)}
                  className={inputClass}
                >
                  <option value="">None</option>
                  {wallets.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {money(w.balance, w.currency)}
                    </option>
                  ))}
                </select>
              </Field>
            </>
          )}

          <Field label="Description">
            <input
              type="text"
              placeholder="Optional"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className={inputClass}
            />
          </Field>

          <Field label="Date">
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
            disabled={submitting}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Add transaction'}
          </button>
        </form>
      </section>

      {/* List */}
      <section className="md:col-span-2">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          Transactions
        </h2>
        {error && <p className="mb-3 text-sm text-red-400">{error}</p>}
        {loading ? (
          <p className="text-neutral-500">Loading…</p>
        ) : transactions.length === 0 ? (
          <p className="text-neutral-500">No transactions yet.</p>
        ) : (
          <div className="overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-900">
            <table className="w-full text-sm">
              <thead className="bg-neutral-950/40 text-left text-neutral-400">
                <tr>
                  <th className="px-4 py-2.5 font-medium">Date</th>
                  <th className="px-4 py-2.5 font-medium">Category</th>
                  <th className="px-4 py-2.5 font-medium">Description</th>
                  <th className="px-4 py-2.5 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((t) => {
                  const xfer = t.type === 'TRANSFER'
                  const color = xfer
                    ? 'text-sky-400'
                    : t.type === 'INCOME'
                      ? 'text-emerald-400'
                      : 'text-rose-400'
                  const prefix = xfer ? '⇄ ' : t.type === 'INCOME' ? '+' : '-'
                  return (
                    <tr key={t.id} className="border-t border-neutral-800 transition hover:bg-neutral-800/40">
                      <td className="px-4 py-2.5 text-neutral-500">{t.transactionDate}</td>
                      <td className="px-4 py-2.5 text-neutral-200">{categoryName(categories, t.categoryId)}</td>
                      <td className="px-4 py-2.5 text-neutral-500">{t.description ?? '—'}</td>
                      <td className={`px-4 py-2.5 text-right font-semibold ${color}`}>
                        {prefix}
                        {money(t.amount, t.currency)}
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
  )
}
