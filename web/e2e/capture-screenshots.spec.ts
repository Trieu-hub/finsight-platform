import { expect, test } from '@playwright/test'
import { mkdirSync } from 'node:fs'

/**
 * Captures the manifest screenshots — not a test, a generator.
 *
 * `manifest.screenshots` is what makes Chromium show the rich, app-store-like install dialog
 * instead of the one-line minimal one. It needs real images of the real app, so this drives a
 * real browser rather than mocking anything. It is skipped by default: it signs in, which means
 * it needs an account and a running stack, and neither belongs in the normal suite.
 *
 *   E2E_BASE_URL=https://vernfy.com SHOT_EMAIL=... SHOT_PASSWORD=... \
 *     npx playwright test capture-screenshots --project=chromium
 *
 * Sizes follow the two `form_factor` values Chromium distinguishes: `narrow` (phone) drives the
 * mobile install sheet, `wide` (desktop) the desktop one. A manifest listing only `wide` gets no
 * rich dialog on a phone at all, which is where installs actually happen.
 */
const EMAIL = process.env.SHOT_EMAIL
const PASSWORD = process.env.SHOT_PASSWORD
const OUT = 'public/screenshots'

test.skip(!EMAIL || !PASSWORD, 'set SHOT_EMAIL and SHOT_PASSWORD to capture screenshots')

/*
 * One screen, because only one had anything on it.
 *
 * /analytics, /budgets and /transactions all default to the CURRENT month, and the capture
 * account's data sits in an earlier one — so they came back as "No transactions were recorded
 * for August 2026", "No budgets yet", an empty form. The run passed every time; the images were
 * useless. An empty state is a worse advert than no screenshot at all.
 *
 * Add screens back here when the account has current-month data — and open the PNGs afterwards
 * rather than trusting the green run.
 */
const SCREENS = [{ path: '/', name: 'dashboard' }]

for (const form of [
  { factor: 'narrow', width: 412, height: 915 },
  { factor: 'wide', width: 1280, height: 800 },
] as const) {
  test(`capture ${form.factor} screenshots`, async ({ page }) => {
    // Three screens, each with a settle delay, plus a sign-in against a live backend.
    test.setTimeout(180_000)
    mkdirSync(OUT, { recursive: true })
    await page.setViewportSize({ width: form.width, height: form.height })

    // Mark the onboarding tour as already seen, BEFORE the app boots. Without this every shot
    // came back as Vera's welcome card over a blurred page — technically a screenshot of the app,
    // and useless as one. Caught only by opening the PNGs; the run itself passed.
    await page.addInitScript(() => {
      window.localStorage.setItem('vernfy_onboarding_v1', '1')
      window.localStorage.removeItem('vernfy_signup_at')
      // Pin the dark theme so the shots match the manifest's own theme_color/background_color
      // (#0A0A0A). Left to the browser's preference the install dialog shows a light app framed
      // in a dark chrome, which reads as a rendering fault rather than a choice.
      window.localStorage.setItem('vernfy.theme', 'dark')
    })

    await page.goto('/login')
    await page.getByLabel('Email').fill(EMAIL!)
    await page.getByLabel('Password').fill(PASSWORD!)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/$/, { timeout: 30_000 })

    for (const screen of SCREENS) {
      await page.goto(screen.path)
      await page.waitForLoadState('load')
      // NOT `networkidle`: the app holds an SSE connection open for notifications, so the network
      // is never idle and that wait can only ever time out. A settle delay is the honest tool
      // here — this is a screenshot generator, and shooting early captures loading skeletons,
      // which is a worse advert than no screenshot at all.
      await page.waitForTimeout(2500)
      await page.screenshot({ path: `${OUT}/${screen.name}-${form.factor}.png` })
    }
  })
}
