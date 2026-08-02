import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import type { Category, Transaction } from '../api/types'
import Transactions from './Transactions'

// Only the network is faked; the form, its state and the category/type invariant are the real ones.
vi.mock('../api/endpoints', () => ({
  listTransactions: vi.fn(),
  listCategories: vi.fn(),
  listWallets: vi.fn(),
  listBudgets: vi.fn(),
  createTransaction: vi.fn(),
}))
const { listTransactions, listCategories, listWallets, listBudgets, createTransaction } =
  await import('../api/endpoints')

const CATEGORIES: Category[] = [
  { id: 1, name: 'Salary', type: 'INCOME' },
  { id: 4, name: 'Food & Dining', type: 'EXPENSE' },
  { id: 15, name: 'Transfer', type: 'TRANSFER' },
]

/** Order in the form: type, currency, category, wallet — the labels are not wired to the controls. */
const selects = () => Array.from(document.querySelectorAll<HTMLSelectElement>('form select'))
const typeSelect = () => selects()[0]
const categorySelect = () => selects()[2]

const renderPage = () =>
  render(
    <I18nProvider>
      <Transactions />
    </I18nProvider>,
  )

describe('Transactions', () => {
  beforeEach(() => {
    vi.mocked(listTransactions).mockReset().mockResolvedValue([])
    vi.mocked(listWallets).mockReset().mockResolvedValue([])
    vi.mocked(listBudgets).mockReset().mockResolvedValue([])
    vi.mocked(listCategories).mockReset().mockResolvedValue(CATEGORIES)
    vi.mocked(createTransaction).mockReset().mockResolvedValue({} as Transaction)
  })

  it('defaults the category to one of the current type', async () => {
    renderPage()

    await waitFor(() => expect(categorySelect().value).toBe('4'))
  })

  it('moves the category to the new type instead of keeping a contradictory pair', async () => {
    renderPage()
    await waitFor(() => expect(categorySelect().value).toBe('4'))

    fireEvent.change(typeSelect(), { target: { value: 'INCOME' } })

    await waitFor(() => expect(categorySelect().value).toBe('1'))
  })

  it('still posts a real category when the type changed before the categories arrived', async () => {
    // The regression this guards: with nothing to pick from yet the category was left empty, and
    // because an empty value matches no <option> the browser still *displayed* the first one. The
    // form looked filled in and posted categoryId 0, which the API rejects.
    let resolveCategories: (c: Category[]) => void = () => {}
    vi.mocked(listCategories).mockReturnValue(
      new Promise<Category[]>((resolve) => {
        resolveCategories = resolve
      }),
    )
    renderPage()

    // The user is quicker than the network.
    fireEvent.change(typeSelect(), { target: { value: 'INCOME' } })
    resolveCategories(CATEGORIES)

    await waitFor(() => expect(categorySelect().value).toBe('1'))

    fireEvent.change(screen.getByPlaceholderText('0'), { target: { value: '1250000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add transaction' }))

    await waitFor(() => expect(createTransaction).toHaveBeenCalled())
    expect(vi.mocked(createTransaction).mock.calls[0][0]).toMatchObject({
      type: 'INCOME',
      categoryId: 1,
    })
  })
})
