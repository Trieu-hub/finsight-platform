import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import type { AnalyticsOverview, MonthlySummary, SpendForecast } from '../api/types'
import Analytics from './Analytics'

// Only the network is faked. What is under test is that the page tells the two projections
// apart: the backend answers with either the run rate or a trained model, and for a while the
// page rendered both as "at the current pace" — which is wrong for one of them.
vi.mock('../api/endpoints', () => ({
  analyticsOverview: vi.fn(),
  analyticsCategories: vi.fn(),
  analyticsForecast: vi.fn(),
  analyticsSummary: vi.fn(),
}))
const { analyticsOverview, analyticsCategories, analyticsForecast, analyticsSummary } =
  await import('../api/endpoints')

const OVERVIEW: AnalyticsOverview = {
  yearMonth: '2026-08',
  currency: 'USD',
  income: 5000,
  expense: 2000,
  net: 3000,
  savingsRate: 60,
  prevIncome: 5000,
  prevExpense: 2500,
  prevNet: 2500,
  prevSavingsRate: 50,
  incomeChangePct: 0,
  expenseChangePct: -20,
  topMovers: [],
}

const SUMMARY: MonthlySummary = {
  yearMonth: '2026-08',
  currency: 'USD',
  summary: 'A calm month.',
  aiGenerated: false,
}

const RUN_RATE: SpendForecast = {
  yearMonth: '2026-08',
  currency: 'USD',
  expenseToDate: 300,
  dayOfMonth: 10,
  daysInMonth: 31,
  projectedExpense: 930,
  dailyAverage: 30,
  method: 'RUN_RATE',
  projectedLow: null,
  projectedHigh: null,
}

const renderPage = () =>
  render(
    <I18nProvider>
      <Analytics />
    </I18nProvider>,
  )

describe('Analytics forecast', () => {
  beforeEach(() => {
    vi.mocked(analyticsOverview).mockReset().mockResolvedValue(OVERVIEW)
    vi.mocked(analyticsCategories).mockReset().mockResolvedValue([])
    vi.mocked(analyticsSummary).mockReset().mockResolvedValue(SUMMARY)
    vi.mocked(analyticsForecast).mockReset().mockResolvedValue(RUN_RATE)
  })

  it('says the run rate answered, and shows no band it does not have', async () => {
    renderPage()

    expect(await screen.findByText('Current pace')).toBeInTheDocument()
    expect(screen.getByText(/at the current pace/)).toBeInTheDocument()
    // The run rate carries no error estimate, so inventing a range would be a lie.
    expect(screen.queryByText(/likely between/)).not.toBeInTheDocument()
  })

  it('says the model answered, and shows the band it reported', async () => {
    vi.mocked(analyticsForecast).mockResolvedValue({
      ...RUN_RATE,
      method: 'MODEL',
      projectedExpense: 1010,
      projectedLow: 900,
      projectedHigh: 1120,
    })

    renderPage()

    expect(await screen.findByText('Trained model')).toBeInTheDocument()
    expect(screen.getByText(/learned weekly pattern/)).toBeInTheDocument()
    // The regression this guards: the caption used to read "at the current pace" for a number
    // the current pace did not produce.
    expect(screen.queryByText(/at the current pace/)).not.toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByText(/likely between \$900\.00 and \$1,120\.00/)).toBeInTheDocument(),
    )
  })
})
