import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import type { Category } from '../api/types'
import Import from './Import'

// Only the network is faked. What is under test is the preview: whether a row the import will
// refuse says so, and whether its tick agrees with the count and the button.
vi.mock('../api/endpoints', () => ({
  listCategories: vi.fn(),
  listWallets: vi.fn(),
  importTransactions: vi.fn(),
}))
const { listCategories, listWallets } = await import('../api/endpoints')

const CATEGORIES: Category[] = [
  { id: 1, name: 'Salary', type: 'INCOME' },
  { id: 4, name: 'Food & Dining', type: 'EXPENSE' },
]

// A semicolon statement with one good row, one whose amount is blank ("opening balance" lines are
// written this way) and one whose date does not exist.
const STATEMENT = [
  'Ngày;Diễn giải;Số tiền',
  '05/08/2026;Cafe;-85.000',
  '07/08/2026;Số dư đầu kỳ;',
  '31/02/2026;Ngày không tồn tại;-10.000',
].join('\n')

const renderPage = () =>
  render(
    <I18nProvider>
      <Import />
    </I18nProvider>,
  )

async function upload(csv = STATEMENT) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File([csv], 'sao-ke.csv', { type: 'text/csv' })
  fireEvent.change(input, { target: { files: [file] } })
  await waitFor(() => expect(screen.getByText(/3 rows read/)).toBeInTheDocument())
}

const row = (n: number) => screen.getByLabelText<HTMLInputElement>(`Import row ${n}`)

describe('Import', () => {
  beforeEach(() => {
    vi.mocked(listCategories).mockReset().mockResolvedValue(CATEGORIES)
    vi.mocked(listWallets).mockReset().mockResolvedValue([])
  })

  it('says why a row is held back whichever field is unreadable', async () => {
    renderPage()
    await upload()

    fireEvent.change(screen.getByLabelText('Default expense category'), { target: { value: '4' } })
    fireEvent.change(screen.getByLabelText('Default income category'), { target: { value: '1' } })

    // The date is fine on the middle row, so this reason only appears if it is reported next to
    // the description rather than in place of the date.
    expect(await screen.findByText('No amount could be read')).toBeInTheDocument()
    expect(screen.getByText('No date could be read')).toBeInTheDocument()
    expect(screen.getByText('1 ready · 2 need attention')).toBeInTheDocument()
  })

  it('leaves an unimportable row unticked and locked, matching the count', async () => {
    renderPage()
    await upload()

    fireEvent.change(screen.getByLabelText('Default expense category'), { target: { value: '4' } })
    fireEvent.change(screen.getByLabelText('Default income category'), { target: { value: '1' } })
    await screen.findByText('No amount could be read')

    expect(row(1).checked).toBe(true)
    expect(row(1).disabled).toBe(false)
    for (const n of [2, 3]) {
      expect(row(n).checked).toBe(false)
      expect(row(n).disabled).toBe(true)
    }
    expect(screen.getByRole('button', { name: 'Import 1 rows' })).toBeEnabled()
  })

  it('tells the user a category is missing before one is chosen', async () => {
    renderPage()
    await upload()

    // Nothing is importable yet, and the reason must be on screen rather than only in the count.
    // Only the otherwise-good row reports it; the other two fail earlier, on date and amount.
    expect(await screen.findByText('Pick a category')).toBeInTheDocument()
    expect(screen.getByText('0 ready · 3 need attention')).toBeInTheDocument()
  })
})
