import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import DeliverySettings from './DeliverySettings'

// Only the network and the browser-only push plumbing are faked. The rule under test is which
// controls appear: a switch that cannot deliver is worse than no switch, so each channel must be
// absent — not merely disabled — when it is unusable.
vi.mock('../api/endpoints', () => ({
  notificationPreferences: vi.fn(),
  setEmailAlerts: vi.fn(),
}))
vi.mock('../hooks/useWebPush', () => ({ useWebPush: vi.fn() }))

const { notificationPreferences, setEmailAlerts } = await import('../api/endpoints')
const { useWebPush } = await import('../hooks/useWebPush')

type PushState = ReturnType<typeof useWebPush>

function push(state: string): PushState {
  return { state, busy: false, enable: vi.fn(), disable: vi.fn() } as unknown as PushState
}

const prefs = (over: Partial<{ emailEnabled: boolean; emailConfigured: boolean }> = {}) => ({
  emailEnabled: false,
  email: 'user@vernfy.test',
  emailConfigured: true,
  ...over,
})

const renderIt = () =>
  render(
    <I18nProvider>
      <DeliverySettings />
    </I18nProvider>,
  )

describe('DeliverySettings', () => {
  beforeEach(() => {
    vi.mocked(notificationPreferences).mockReset().mockResolvedValue(prefs())
    vi.mocked(setEmailAlerts).mockReset()
    vi.mocked(useWebPush).mockReset().mockReturnValue(push('off'))
  })

  it('renders nothing when neither channel can deliver', async () => {
    vi.mocked(useWebPush).mockReturnValue(push('unsupported'))
    vi.mocked(notificationPreferences).mockResolvedValue(prefs({ emailConfigured: false }))

    const { container } = renderIt()

    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('hides the push row when the server has no VAPID keypair', async () => {
    vi.mocked(useWebPush).mockReturnValue(push('unconfigured'))

    renderIt()

    expect(await screen.findByText('Email alerts')).toBeInTheDocument()
    expect(screen.queryByText('Browser notifications')).not.toBeInTheDocument()
  })

  it('hides the push row when the probe never got an answer', async () => {
    vi.mocked(useWebPush).mockReturnValue(push('error'))

    renderIt()

    expect(await screen.findByText('Email alerts')).toBeInTheDocument()
    expect(screen.queryByText('Browser notifications')).not.toBeInTheDocument()
  })

  it('hides the email row when the server cannot send mail', async () => {
    vi.mocked(notificationPreferences).mockResolvedValue(prefs({ emailConfigured: false }))

    renderIt()

    expect(await screen.findByText('Browser notifications')).toBeInTheDocument()
    expect(screen.queryByText('Email alerts')).not.toBeInTheDocument()
  })

  it('explains a browser-level block instead of offering a button that cannot work', async () => {
    vi.mocked(useWebPush).mockReturnValue(push('denied'))

    renderIt()

    expect(await screen.findByText('Blocked in browser')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Browser notifications' })).not.toBeInTheDocument()
  })

  it('turns email alerts on and reflects what the server stored', async () => {
    vi.mocked(setEmailAlerts).mockResolvedValue(prefs({ emailEnabled: true }))
    renderIt()

    fireEvent.click(await screen.findByRole('button', { name: 'Email alerts' }))

    await waitFor(() => expect(setEmailAlerts).toHaveBeenCalledWith(true))
    expect(await screen.findByRole('button', { name: 'Email alerts' })).toHaveAttribute('aria-pressed', 'true')
  })
})
