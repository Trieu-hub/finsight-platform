import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import type { Category, Transaction } from '../api/types'
import { queued } from '../lib/outbox'
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

const typeSelect = () => screen.getByLabelText<HTMLSelectElement>('Type')
const categorySelect = () => screen.getByLabelText<HTMLSelectElement>('Category')

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

    fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '1250000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add transaction' }))

    await waitFor(() => expect(createTransaction).toHaveBeenCalled())
    expect(vi.mocked(createTransaction).mock.calls[0][0]).toMatchObject({
      type: 'INCOME',
      categoryId: 1,
    })
  })

  describe('while offline', () => {
    beforeEach(() => {
      window.localStorage.clear()
      Object.defineProperty(window.navigator, 'onLine', { value: false, configurable: true })
    })

    afterEach(() => {
      Object.defineProperty(window.navigator, 'onLine', { value: true, configurable: true })
    })

    /*
     * The regression this exists for, found by using the app on a phone in airplane mode: an
     * EXPENSE is required to be charged to a budget, and offline `budgets` holds whatever the
     * cache had — usually nothing. So the guard fired, returned, and the queue was never reached.
     * EXPENSE is the default type, which made the offline write feature unreachable in exactly
     * the situation it was built for. Every automated test passed throughout.
     */
    it('queues an expense even when no budget data could be loaded', async () => {
      renderPage()
      await waitFor(() => expect(categorySelect().value).toBe('4'))

      fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '50000' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add transaction' }))

      await waitFor(() => expect(queued()).toHaveLength(1))
      expect(queued()[0].body).toMatchObject({ type: 'EXPENSE', categoryId: 4, amount: 50000 })
      // And nothing was sent — a request offline can only fail, and a failure here would look to
      // the user like the transaction was lost.
      expect(createTransaction).not.toHaveBeenCalled()
    })

    it('gives the queued write the date the user chose, not the day it is sent', async () => {
      renderPage()
      await waitFor(() => expect(categorySelect().value).toBe('4'))

      fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '1000' } })
      fireEvent.change(screen.getByLabelText('Date'), { target: { value: '2026-06-01' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add transaction' }))

      await waitFor(() => expect(queued()).toHaveLength(1))
      expect(queued()[0].body.transactionDate).toBe('2026-06-01')
    })

    it('tells the user it is holding something, rather than going quiet', async () => {
      renderPage()
      await waitFor(() => expect(categorySelect().value).toBe('4'))

      fireEvent.change(screen.getByLabelText('Amount'), { target: { value: '1000' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add transaction' }))

      // Silence after a save is how a user concludes their transaction vanished.
      expect(await screen.findByText(/waiting to sync/i)).toBeInTheDocument()
    })
  })
})
