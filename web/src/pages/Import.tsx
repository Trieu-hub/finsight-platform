import { useEffect, useMemo, useState, type ChangeEvent, type ReactNode } from 'react'

import { errorMessage } from '../api/client'
import { importTransactions, listCategories, listWallets } from '../api/endpoints'
import type { Category, ImportResult, TransactionType, Wallet } from '../api/types'
import { useI18n } from '../i18n'
import { money } from '../lib/format'
import { detectDelimiter, parseAmount, parseCsv, parseDate, type DateOrder } from '../lib/csv'

// How the file says what is money out and what is money in. A statement with signed amounts is
// self-describing; one exported as a debit-only or credit-only list is not, so the user says which.
type TypeMode = 'SIGN' | 'ALL_EXPENSE' | 'ALL_INCOME'

// Rendering a thousand rows would cost more than it tells the user. Everything is still imported.
const PREVIEW_LIMIT = 50

const fieldBase =
  'rounded-lg border border-neutral-700 bg-neutral-950/60 px-3 py-2 text-neutral-100 outline-none transition focus:border-emerald-500 focus:ring-2 focus:ring-emerald-500/30'
const selectClass = `w-full ${fieldBase}`

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block space-y-1.5">
      <span className="block text-sm font-medium text-neutral-300">{label}</span>
      {children}
    </label>
  )
}

/** One statement line, after the raw cells have been read with the current mapping. */
interface PreparedRow {
  index: number
  date: string | null
  amount: number | null
  description: string
  type: TransactionType
  categoryId: number | null
  problem: string | null
}

export default function Import() {
  const { t } = useI18n()

  const [categories, setCategories] = useState<Category[]>([])
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [loadError, setLoadError] = useState('')

  const [fileName, setFileName] = useState('')
  const [rows, setRows] = useState<string[][]>([])
  const [hasHeader, setHasHeader] = useState(true)

  const [dateCol, setDateCol] = useState(-1)
  const [amountCol, setAmountCol] = useState(-1)
  const [descCol, setDescCol] = useState(-1)
  const [dateOrder, setDateOrder] = useState<DateOrder>('DMY')
  const [typeMode, setTypeMode] = useState<TypeMode>('SIGN')

  const [walletId, setWalletId] = useState('')
  const [currency, setCurrency] = useState('VND')
  const [expenseCategoryId, setExpenseCategoryId] = useState('')
  const [incomeCategoryId, setIncomeCategoryId] = useState('')
  // Per-row category overrides, keyed by the row's position in the file.
  const [overrides, setOverrides] = useState<Record<number, number>>({})
  const [skipped, setSkipped] = useState<Record<number, boolean>>({})

  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([listCategories(), listWallets()])
      .then(([cats, ws]) => {
        setCategories(cats)
        setWallets(ws)
      })
      .catch((err) => setLoadError(errorMessage(err)))
  }, [])

  const selectedWallet = useMemo(
    () => wallets.find((w) => String(w.id) === walletId),
    [wallets, walletId],
  )
  // A wallet fixes the currency, exactly as the manual form does — the backend refuses a
  // transaction whose currency differs from its wallet's.
  const effectiveCurrency = selectedWallet?.currency ?? currency

  const expenseCategories = useMemo(
    () => categories.filter((c) => c.type === 'EXPENSE'),
    [categories],
  )
  const incomeCategories = useMemo(() => categories.filter((c) => c.type === 'INCOME'), [categories])

  const header = hasHeader && rows.length > 0 ? rows[0] : null
  const bodyRows = useMemo(() => (hasHeader ? rows.slice(1) : rows), [rows, hasHeader])
  const columnCount = rows.reduce((max, row) => Math.max(max, row.length), 0)

  async function handleFile(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    setResult(null)
    setError('')
    setOverrides({})
    setSkipped({})
    const text = await file.text()
    const parsed = parseCsv(text, detectDelimiter(text))
    setFileName(file.name)
    setRows(parsed)
    guessColumns(parsed)
  }

  /** Names the columns when the header says what they are; otherwise leaves the choice open. */
  function guessColumns(parsed: string[][]) {
    const first = parsed[0] ?? []
    const find = (words: string[]) =>
      first.findIndex((cell) => words.some((w) => cell.toLowerCase().includes(w)))
    setDateCol(find(['date', 'ngày', 'ngay', 'time']))
    setAmountCol(find(['amount', 'số tiền', 'so tien', 'value', 'debit', 'credit']))
    setDescCol(find(['desc', 'detail', 'nội dung', 'noi dung', 'diễn giải', 'dien giai', 'memo']))
  }

  const prepared: PreparedRow[] = useMemo(() => {
    if (dateCol < 0 || amountCol < 0) return []
    return bodyRows.map((cells, index) => {
      const rawAmount = cells[amountCol] ?? ''
      const amount = parseAmount(rawAmount)
      const date = parseDate(cells[dateCol] ?? '', dateOrder)
      const description = (descCol >= 0 ? (cells[descCol] ?? '') : '').trim().slice(0, 500)

      const type: TransactionType =
        typeMode === 'ALL_EXPENSE'
          ? 'EXPENSE'
          : typeMode === 'ALL_INCOME'
            ? 'INCOME'
            : (amount ?? 0) < 0
              ? 'EXPENSE'
              : 'INCOME'

      const fallback = type === 'EXPENSE' ? expenseCategoryId : incomeCategoryId
      const categoryId = overrides[index] ?? (fallback ? Number(fallback) : null)

      let problem: string | null = null
      if (date === null) problem = t('import.badDate')
      else if (amount === null) problem = t('import.badAmount')
      else if (amount === 0) problem = t('import.zeroAmount')
      else if (categoryId === null) problem = t('import.noCategory')

      return { index, date, amount, description, type, categoryId, problem }
    })
  }, [
    bodyRows,
    dateCol,
    amountCol,
    descCol,
    dateOrder,
    typeMode,
    expenseCategoryId,
    incomeCategoryId,
    overrides,
    t,
  ])

  const importable = prepared.filter((row) => !row.problem && !skipped[row.index])
  const problemCount = prepared.filter((row) => row.problem && !skipped[row.index]).length

  async function handleImport() {
    setError('')
    setResult(null)
    setSubmitting(true)
    try {
      const payload = importable.map((row) => ({
        type: row.type,
        amount: Math.abs(row.amount as number),
        currency: effectiveCurrency,
        categoryId: row.categoryId as number,
        description: row.description || undefined,
        transactionDate: row.date as string,
        walletId: selectedWallet ? selectedWallet.id : undefined,
      }))
      setResult(await importTransactions(payload))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  const columnOptions = Array.from({ length: columnCount }, (_, i) => (
    <option key={i} value={i}>
      {header?.[i]?.trim() || t('import.columnN', { n: i + 1 })}
    </option>
  ))

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold text-neutral-100">{t('import.title')}</h1>
        <p className="mt-1 text-sm text-neutral-400">{t('import.subtitle')}</p>
      </header>

      {loadError && (
        <p className="rounded-lg border border-red-800 bg-neutral-900/40 px-4 py-3 text-sm text-red-400">
          {loadError}
        </p>
      )}

      <section className="rounded-xl border border-neutral-800 bg-neutral-900/40 p-5">
        <Field label={t('import.file')}>
          <input
            type="file"
            accept=".csv,text/csv,text/plain"
            onChange={handleFile}
            className={`${selectClass} file:mr-3 file:rounded-md file:border-0 file:bg-emerald-600 file:px-3 file:py-1 file:text-white`}
          />
        </Field>
        {fileName && (
          <p className="mt-2 text-sm text-neutral-400">
            {t('import.loaded', { file: fileName, n: bodyRows.length })}
          </p>
        )}
      </section>

      {rows.length > 0 && (
        <section className="space-y-4 rounded-xl border border-neutral-800 bg-neutral-900/40 p-5">
          <h2 className="text-lg font-medium text-neutral-100">{t('import.mapping')}</h2>

          <label className="flex items-center gap-2 text-sm text-neutral-300">
            <input
              type="checkbox"
              checked={hasHeader}
              onChange={(e) => setHasHeader(e.target.checked)}
              className="h-4 w-4 rounded border-neutral-600 bg-neutral-950"
            />
            {t('import.hasHeader')}
          </label>

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <Field label={t('import.dateColumn')}>
              <select
                value={dateCol}
                onChange={(e) => setDateCol(Number(e.target.value))}
                className={selectClass}
              >
                <option value={-1}>{t('common.select')}</option>
                {columnOptions}
              </select>
            </Field>
            <Field label={t('import.amountColumn')}>
              <select
                value={amountCol}
                onChange={(e) => setAmountCol(Number(e.target.value))}
                className={selectClass}
              >
                <option value={-1}>{t('common.select')}</option>
                {columnOptions}
              </select>
            </Field>
            <Field label={t('import.descColumn')}>
              <select
                value={descCol}
                onChange={(e) => setDescCol(Number(e.target.value))}
                className={selectClass}
              >
                <option value={-1}>{t('common.none')}</option>
                {columnOptions}
              </select>
            </Field>
            <Field label={t('import.dateOrder')}>
              <select
                value={dateOrder}
                onChange={(e) => setDateOrder(e.target.value as DateOrder)}
                className={selectClass}
              >
                <option value="DMY">{t('import.dmy')}</option>
                <option value="MDY">{t('import.mdy')}</option>
                <option value="YMD">{t('import.ymd')}</option>
              </select>
            </Field>
            <Field label={t('import.typeMode')}>
              <select
                value={typeMode}
                onChange={(e) => setTypeMode(e.target.value as TypeMode)}
                className={selectClass}
              >
                <option value="SIGN">{t('import.bySign')}</option>
                <option value="ALL_EXPENSE">{t('import.allExpense')}</option>
                <option value="ALL_INCOME">{t('import.allIncome')}</option>
              </select>
            </Field>
            <Field label={t('import.wallet')}>
              <select
                value={walletId}
                onChange={(e) => setWalletId(e.target.value)}
                className={selectClass}
              >
                <option value="">{t('import.noWallet')}</option>
                {wallets.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name} ({w.currency})
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('import.currency')}>
              <select
                value={effectiveCurrency}
                onChange={(e) => setCurrency(e.target.value)}
                disabled={Boolean(selectedWallet)}
                title={selectedWallet ? t('import.currencyLocked') : undefined}
                className={`${selectClass} disabled:opacity-60`}
              >
                <option value="VND">VND</option>
                <option value="USD">USD</option>
              </select>
            </Field>
            <Field label={t('import.expenseCategory')}>
              <select
                value={expenseCategoryId}
                onChange={(e) => setExpenseCategoryId(e.target.value)}
                className={selectClass}
              >
                <option value="">{t('common.select')}</option>
                {expenseCategories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('import.incomeCategory')}>
              <select
                value={incomeCategoryId}
                onChange={(e) => setIncomeCategoryId(e.target.value)}
                className={selectClass}
              >
                <option value="">{t('common.select')}</option>
                {incomeCategories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <p className="text-sm text-neutral-400">{t('import.budgetNote')}</p>
        </section>
      )}

      {prepared.length > 0 && (
        <section className="space-y-4 rounded-xl border border-neutral-800 bg-neutral-900/40 p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg font-medium text-neutral-100">{t('import.preview')}</h2>
            <p className="text-sm text-neutral-400">
              {t('import.counts', { ok: importable.length, bad: problemCount })}
            </p>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full min-w-[46rem] text-left text-sm">
              <thead className="text-neutral-400">
                <tr>
                  <th className="px-2 py-2 font-medium">{t('import.colInclude')}</th>
                  <th className="px-2 py-2 font-medium">{t('import.colDate')}</th>
                  <th className="px-2 py-2 font-medium">{t('import.colDescription')}</th>
                  <th className="px-2 py-2 text-right font-medium">{t('import.colAmount')}</th>
                  <th className="px-2 py-2 font-medium">{t('import.colCategory')}</th>
                </tr>
              </thead>
              <tbody>
                {prepared.slice(0, PREVIEW_LIMIT).map((row) => {
                  const options = row.type === 'EXPENSE' ? expenseCategories : incomeCategories
                  const excluded = Boolean(skipped[row.index])
                  return (
                    <tr
                      key={row.index}
                      className={`border-t border-neutral-800 ${excluded ? 'opacity-40' : ''}`}
                    >
                      <td className="px-2 py-2">
                        {/* A row with a problem is not written whatever the user ticks, so it
                            shows unticked rather than promising an import that will not happen. */}
                        <input
                          type="checkbox"
                          checked={!excluded && !row.problem}
                          disabled={Boolean(row.problem)}
                          aria-label={t('import.includeRow', { n: row.index + 1 })}
                          onChange={(e) =>
                            setSkipped((prev) => ({ ...prev, [row.index]: !e.target.checked }))
                          }
                          className="h-4 w-4 rounded border-neutral-600 bg-neutral-950 disabled:opacity-40"
                        />
                      </td>
                      <td className="px-2 py-2 text-neutral-200">{row.date ?? '—'}</td>
                      <td className="max-w-[18rem] px-2 py-2 text-neutral-300">
                        <span className="block truncate">{row.description}</span>
                        {/* Beside the description, not inside the date cell: the reason a row is
                            held back is just as often the amount or the missing category. */}
                        {row.problem && (
                          <span className="block text-xs text-red-400">{row.problem}</span>
                        )}
                      </td>
                      <td
                        className={`px-2 py-2 text-right ${row.type === 'EXPENSE' ? 'text-red-300' : 'text-emerald-300'}`}
                      >
                        {row.amount === null
                          ? '—'
                          : money(Math.abs(row.amount), effectiveCurrency)}
                      </td>
                      <td className="px-2 py-2">
                        <select
                          value={row.categoryId ?? ''}
                          aria-label={t('import.categoryForRow', { n: row.index + 1 })}
                          onChange={(e) =>
                            setOverrides((prev) => ({ ...prev, [row.index]: Number(e.target.value) }))
                          }
                          className={`${fieldBase} w-40 py-1 text-sm`}
                        >
                          <option value="">{t('common.select')}</option>
                          {options.map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.name}
                            </option>
                          ))}
                        </select>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {prepared.length > PREVIEW_LIMIT && (
            <p className="text-sm text-neutral-400">
              {t('import.previewCapped', { shown: PREVIEW_LIMIT, total: prepared.length })}
            </p>
          )}

          {error && (
            <p className="rounded-lg border border-red-800 bg-neutral-900/40 px-4 py-3 text-sm text-red-400">
              {error}
            </p>
          )}

          <button
            type="button"
            onClick={handleImport}
            disabled={submitting || importable.length === 0}
            className="rounded-lg bg-emerald-600 px-4 py-2 font-medium text-white transition hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {submitting ? t('import.importing') : t('import.import', { n: importable.length })}
          </button>
        </section>
      )}

      {result && (
        <section className="space-y-2 rounded-xl border border-emerald-800 bg-neutral-900/40 p-5">
          {/* Neutral ink, not emerald: the neutral ramp is what flips with the theme (index.css),
              so accent-coloured text would be light-on-light in light mode. */}
          <h2 className="text-lg font-medium text-neutral-100">{t('import.done')}</h2>
          <p className="text-sm text-neutral-200">
            {t('import.result', { imported: result.imported, duplicates: result.duplicates })}
          </p>
          {result.errors.length > 0 && (
            <ul className="list-inside list-disc text-sm text-red-400">
              {result.errors.map((e) => (
                <li key={e.row}>{t('import.rowFailed', { n: e.row, message: e.message })}</li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}
