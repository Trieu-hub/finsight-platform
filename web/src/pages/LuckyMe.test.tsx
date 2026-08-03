import { fireEvent, render, screen, act } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import LuckyMe from './LuckyMe'

// Admin, because the coin is only rigged green for admins (`!isAdmin && Math.random() < ...`);
// anything else makes this test a 1-in-4 coin toss of its own.
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ isAdmin: true, signOut: vi.fn() }),
}))

const SPIN_MS = 2600

describe('LuckyMe lobby', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('offers each playable game as a real button named after that game', () => {
    render(
      <MemoryRouter>
        <I18nProvider>
          <LuckyMe />
        </I18nProvider>
      </MemoryRouter>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Flip the coin' }))
    act(() => {
      vi.advanceTimersByTime(SPIN_MS + 100)
    })

    // The regression this guards: the card is a clickable <article>, which takes no focus and
    // never reaches the accessibility tree — so with no button inside it, the games were
    // openable by pointer only. Querying by role is exactly what a keyboard or screen reader
    // can reach, which is why this asserts by role rather than by text.
    const play = screen.getByRole('button', { name: 'Play Roulette' })
    expect(play).toBeInTheDocument()

    // Named after the game, not just "Play": a screen-reader user hearing the same label on
    // every card learns nothing about which one they are on.
    expect(play).toHaveAccessibleName(/Roulette/)
  })
})
