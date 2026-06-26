import { useEffect, useState, type FormEvent } from 'react'
import {
  createTransaction,
  listCategories,
  listTransactions,
} from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Category, Transaction, TransactionType } from '../api/types'
import { categoryName, money } from '../lib/format'

const today = () => new Date().toISOString().slice(0, 10)

const inputClass =
  'w-full rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2 text-neutral-100 placeholder-neutral-500 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/30'

export default function Transactions() {
  const [transactions, setTransactions] = useState<Transaction[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  // form state
  const [type, setType] = useState<TransactionType>('EXPENSE')
  const [amount, setAmount] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [description, setDescription] = useState('')
  const [date, setDate] = useState(today())
  const [submitting, setSubmitting] = useState(false)

  async function load() {
    try {
      const [tx, cats] = await Promise.all([listTransactions(), listCategories()])
      setTransactions(tx)
      setCategories(cats)
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

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setSubmitting(true)
    try {
      await createTransaction({
        type,
        amount: Number(amount),
        currency: 'USD',
        categoryId: Number(categoryId),
        description: description || undefined,
        transactionDate: date,
      })
      setAmount('')
      setDescription('')
      await load()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="grid gap-6 md:grid-cols-3">
      {/* Create form */}
      <section className="md:col-span-1">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          New transaction
        </h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-3 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <select
            value={type}
            onChange={(e) => {
              const next = e.target.value as TransactionType
              setType(next)
              // Reset the category to one valid for the new type, so the pair is
              // never contradictory (e.g. EXPENSE + Salary).
              const firstOfType = categories.find((c) => c.type === next)
              setCategoryId(firstOfType ? String(firstOfType.id) : '')
            }}
            className={inputClass}
          >
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </select>
          <input
            type="number"
            placeholder="Amount"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            min="0.01"
            step="0.01"
            required
            className={inputClass}
          />
          <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} className={inputClass}>
            {categories
              .filter((c) => c.type === type)
              .map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
          </select>
          <input
            type="text"
            placeholder="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className={inputClass}
          />
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} required className={inputClass} />
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
                {transactions.map((t) => (
                  <tr key={t.id} className="border-t border-neutral-800 transition hover:bg-neutral-800/40">
                    <td className="px-4 py-2.5 text-neutral-500">{t.transactionDate}</td>
                    <td className="px-4 py-2.5 text-neutral-200">{categoryName(categories, t.categoryId)}</td>
                    <td className="px-4 py-2.5 text-neutral-500">{t.description ?? '—'}</td>
                    <td
                      className={`px-4 py-2.5 text-right font-semibold ${
                        t.type === 'INCOME' ? 'text-emerald-400' : 'text-rose-400'
                      }`}
                    >
                      {t.type === 'INCOME' ? '+' : '-'}
                      {money(t.amount, t.currency)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
