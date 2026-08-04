import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import DeliverySettings from './DeliverySettings'

// Only the network and the browser-only push plumbing are faked. The rule under test is which
// controls appear: a switch that cannot deliver is worse than no switch, so email and push must be
// absent — not merely disabled — when they are unusable. Webhook and digest have no such condition:
// they need nothing configured on the server, so they are always offered.
vi.mock('../api/endpoints', () => ({
  notificationPreferences: vi.fn(),
  setEmailAlerts: vi.fn(),
  setWebhook: vi.fn(),
  setDigestMode: vi.fn(),
}))
vi.mock('../hooks/useWebPush', () => ({ useWebPush: vi.fn() }))

const { notificationPreferences, setEmailAlerts, setWebhook, setDigestMode } = await import(
  '../api/endpoints'
)
const { useWebPush } = await import('../hooks/useWebPush')

type PushState = ReturnType<typeof useWebPush>

function push(state: string): PushState {
  return { state, busy: false, enable: vi.fn(), disable: vi.fn() } as unknown as PushState
}

const prefs = (
  over: Partial<{
    emailEnabled: boolean
    emailConfigured: boolean
    webhookEnabled: boolean
    webhookUrl: string | null
    webhookSecret: string | null
    digestMode: string
  }> = {},
) =>
  ({
    emailEnabled: false,
    email: 'user@vernfy.test',
    emailConfigured: true,
    webhookEnabled: false,
    webhookUrl: null,
    webhookSecret: null,
    digestMode: 'IMMEDIATE',
    ...over,
  }) as Awaited<ReturnType<typeof notificationPreferences>>

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
    vi.mocked(setWebhook).mockReset()
    vi.mocked(setDigestMode).mockReset()
    vi.mocked(useWebPush).mockReset().mockReturnValue(push('off'))
  })

  it('still offers webhook and digest when neither push nor email can deliver', async () => {
    vi.mocked(useWebPush).mockReturnValue(push('unsupported'))
    vi.mocked(notificationPreferences).mockResolvedValue(prefs({ emailConfigured: false }))

    renderIt()

    expect(await screen.findByLabelText('Webhook')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'As they happen' })).toBeInTheDocument()
    expect(screen.queryByText('Email alerts')).not.toBeInTheDocument()
    expect(screen.queryByText('Browser notifications')).not.toBeInTheDocument()
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

  it('shows the signing secret once, when saving a webhook mints one', async () => {
    // The server returns it on exactly this one response. If the UI does not show it here the
    // user has no way to get it back short of changing the URL.
    vi.mocked(setWebhook).mockResolvedValue(
      prefs({ webhookUrl: 'https://hook.test/x', webhookEnabled: true, webhookSecret: 'whsec_abc123' }),
    )
    renderIt()

    fireEvent.change(await screen.findByLabelText('Webhook'), {
      target: { value: 'https://hook.test/x' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(setWebhook).toHaveBeenCalledWith('https://hook.test/x', true))
    expect(await screen.findByText('whsec_abc123')).toBeInTheDocument()
  })

  it("surfaces the server's reason for refusing a URL", async () => {
    // "must use https" / "must point at a public address" is the only place the SSRF rules are
    // visible to whoever is configuring this. Swallowing it would leave a button that does nothing.
    vi.mocked(setWebhook).mockRejectedValue({
      response: { data: { error: { message: 'Webhook URL must use https' } } },
    })
    renderIt()

    fireEvent.change(await screen.findByLabelText('Webhook'), {
      target: { value: 'http://hook.test/x' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Webhook URL must use https')
  })

  it('clears the webhook, and the secret with it', async () => {
    vi.mocked(notificationPreferences).mockResolvedValue(
      prefs({ webhookUrl: 'https://hook.test/x', webhookEnabled: true }),
    )
    vi.mocked(setWebhook).mockResolvedValue(prefs())
    renderIt()

    fireEvent.click(await screen.findByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(setWebhook).toHaveBeenCalledWith(null, false))
  })

  it('switches digest mode and marks the chosen one', async () => {
    vi.mocked(setDigestMode).mockResolvedValue(prefs({ digestMode: 'DAILY' }))
    renderIt()

    fireEvent.click(await screen.findByRole('button', { name: 'Daily summary' }))

    await waitFor(() => expect(setDigestMode).toHaveBeenCalledWith('DAILY'))
    expect(await screen.findByRole('button', { name: 'Daily summary' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })
})
