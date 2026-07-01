import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { createWallet, deleteWallet, listWallets } from '../api/endpoints'
import { errorMessage } from '../api/client'
import type { Wallet, WalletKind } from '../api/types'
import { groupThousands, money } from '../lib/format'

const CURRENCIES = ['VND', 'USD'] as const
const KINDS: WalletKind[] = ['CASH', 'BANK', 'CARD', 'SAVINGS', 'OTHER']

const fieldBase =
  'rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2 text-neutral-100 placeholder-neutral-500 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/30'
const inputClass = `w-full ${fieldBase}`

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="block text-sm font-medium text-neutral-300">{label}</label>
      {children}
    </div>
  )
}

const kindLabel: Record<WalletKind, string> = {
  CASH: 'Cash',
  BANK: 'Bank',
  CARD: 'Card',
  SAVINGS: 'Savings',
  OTHER: 'Other',
}

export default function Wallets() {
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const [name, setName] = useState('')
  const [type, setType] = useState<WalletKind>('CASH')
  const [currency, setCurrency] = useState<string>('VND')
  const [initialBalance, setInitialBalance] = useState('')
  const [submitting, setSubmitting] = useState(false)

  async function load() {
    try {
      setWallets(await listWallets())
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
    if (!name.trim()) {
      setError('Enter a wallet name.')
      return
    }
    setSubmitting(true)
    try {
      await createWallet({
        name: name.trim(),
        type,
        currency,
        initialBalance: initialBalance ? Number(initialBalance) : 0,
      })
      setName('')
      setInitialBalance('')
      await load()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDelete(w: Wallet) {
    setError('')
    try {
      await deleteWallet(w.id)
      await load()
    } catch (err) {
      // The API rejects deleting a wallet that still holds a balance.
      setError(errorMessage(err))
    }
  }

  const total = wallets.reduce((sum, w) => sum + w.balance, 0)
  const singleCurrency = wallets.length > 0 && wallets.every((w) => w.currency === wallets[0].currency)

  return (
    <div className="grid gap-6 md:grid-cols-3">
      <section className="md:col-span-1">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-neutral-400">
          New wallet
        </h2>
        <form
          onSubmit={handleSubmit}
          className="space-y-4 rounded-2xl border border-neutral-800 bg-neutral-900 p-5"
        >
          <Field label="Name">
            <input
              type="text"
              placeholder="e.g. Checking"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={inputClass}
            />
          </Field>

          <Field label="Type">
            <select
              value={type}
              onChange={(e) => setType(e.target.value as WalletKind)}
              className={inputClass}
            >
              {KINDS.map((k) => (
                <option key={k} value={k}>
                  {kindLabel[k]}
                </option>
              ))}
            </select>
          </Field>

          <Field label="Opening balance">
            <div className="flex gap-2">
              <input
                type="text"
                inputMode="numeric"
                placeholder="0"
                value={groupThousands(initialBalance)}
                onChange={(e) => setInitialBalance(e.target.value.replace(/\D/g, ''))}
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

          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-lg bg-emerald-600 py-2.5 font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500 disabled:opacity-60"
          >
            {submitting ? 'Saving…' : 'Add wallet'}
          </button>
        </form>
      </section>

      <section className="md:col-span-2">
        <div className="mb-3 flex items-baseline justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-neutral-400">Wallets</h2>
          {singleCurrency && (
            <span className="text-sm text-neutral-400">
              Total{' '}
              <span className="font-semibold text-neutral-100">
                {money(total, wallets[0].currency)}
              </span>
            </span>
          )}
        </div>
        {error && <p className="mb-3 text-sm text-red-400">{error}</p>}
        {loading ? (
          <p className="text-neutral-500">Loading…</p>
        ) : wallets.length === 0 ? (
          <p className="text-neutral-500">No wallets yet. Create one to track balances and transfers.</p>
        ) : (
          <div className="space-y-3">
            {wallets.map((w) => (
              <div
                key={w.id}
                className="flex items-center justify-between rounded-2xl border border-neutral-800 bg-neutral-900 p-4 transition hover:border-neutral-700"
              >
                <div>
                  <div className="font-medium text-neutral-200">{w.name}</div>
                  <div className="mt-0.5 text-xs uppercase tracking-wide text-neutral-500">
                    {kindLabel[w.type]} · {w.currency}
                  </div>
                </div>
                <div className="flex items-center gap-4">
                  <span
                    className={`font-semibold ${w.balance < 0 ? 'text-rose-400' : 'text-neutral-100'}`}
                  >
                    {money(w.balance, w.currency)}
                  </span>
                  <button
                    onClick={() => handleDelete(w)}
                    title="Delete wallet"
                    className="rounded-lg border border-neutral-800 px-2.5 py-1 text-xs text-neutral-400 transition hover:border-rose-800 hover:bg-rose-950/40 hover:text-rose-300"
                  >
                    Delete
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
