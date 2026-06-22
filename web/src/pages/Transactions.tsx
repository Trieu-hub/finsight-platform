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
        <h2 className="mb-3 text-lg font-semibold">New transaction</h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-3 rounded-xl border border-slate-200 bg-white p-4"
        >
          <select
            value={type}
            onChange={(e) => setType(e.target.value as TransactionType)}
            className="w-full rounded-md border border-slate-300 px-3 py-2"
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
          <input
            type="text"
            placeholder="Description (optional)"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          />
          <input
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            required
            className="w-full rounded-md border border-slate-300 px-3 py-2"
          />
          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-md bg-indigo-600 py-2 font-medium text-white hover:bg-indigo-700 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Add transaction'}
          </button>
        </form>
      </section>

      {/* List */}
      <section className="md:col-span-2">
        <h2 className="mb-3 text-lg font-semibold">Transactions</h2>
        {error && <p className="mb-3 text-sm text-red-600">{error}</p>}
        {loading ? (
          <p className="text-slate-500">Loading…</p>
        ) : transactions.length === 0 ? (
          <p className="text-slate-500">No transactions yet.</p>
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 text-left text-slate-500">
                <tr>
                  <th className="px-4 py-2 font-medium">Date</th>
                  <th className="px-4 py-2 font-medium">Category</th>
                  <th className="px-4 py-2 font-medium">Description</th>
                  <th className="px-4 py-2 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {transactions.map((t) => (
                  <tr key={t.id} className="border-t border-slate-100">
                    <td className="px-4 py-2 text-slate-500">{t.transactionDate}</td>
                    <td className="px-4 py-2">{categoryName(categories, t.categoryId)}</td>
                    <td className="px-4 py-2 text-slate-500">{t.description ?? '—'}</td>
                    <td
                      className={`px-4 py-2 text-right font-medium ${
                        t.type === 'INCOME' ? 'text-green-600' : 'text-red-600'
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
