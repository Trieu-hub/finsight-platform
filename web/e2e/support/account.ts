import { expect, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'

/**
 * Account setup shared by the browser journeys. Every spec signs up its own user rather than
 * leaning on seeded data, so the suite runs against any freshly started stack — and so two specs
 * can never see each other's transactions.
 */

/** Unique per run: auth rejects a duplicate username OR email, and CI re-runs against a live DB. */
export function newAccount() {
  const stamp = `${Date.now().toString(36)}${randomBytes(6).toString('hex')}`
  return { username: `e2e${stamp}`, email: `e2e${stamp}@vernfy.test`, password: 'E2ePass123' }
}

/**
 * Waits until the gateway will actually answer for auth, not merely until its container says
 * healthy. A rejected login is a *good* result here: it proves the request reached auth-service.
 * What we are waiting out is 503 "Downstream service is unavailable" — the gateway's fail-fast
 * while auth-service is still coming up, which otherwise surfaces as a registration that silently
 * refuses and a test that looks broken.
 */
async function waitForAuth(page: Page) {
  await expect(async () => {
    const res = await page.request.post('/api/v1/auth/login', {
      data: { email: 'readiness-probe@vernfy.test', password: 'not-a-password' },
      failOnStatusCode: false,
    })
    expect(res.status(), 'gateway still fails fast for auth').toBeLessThan(500)
  }).toPass({ timeout: 90_000, intervals: [1_000, 2_000, 5_000] })
}

export async function signUp(page: Page) {
  const account = newAccount()
  await page.goto('/register')
  await waitForAuth(page)
  await page.getByLabel('Username').fill(account.username)
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Password (min 8 chars)').fill(account.password)
  await page.getByRole('button', { name: 'Create account' }).click()

  // Registration signs the user straight in and lands on the dashboard. Past the readiness probe
  // the request still crosses cold JVMs, so allow more than the 15s default — the same allowance
  // the dashboard fan-out already makes.
  await expect(page).toHaveURL(/\/$/, { timeout: 40_000 })

  // A brand-new account always gets the guided tour; dismiss it so it stops covering the page.
  // It renders a tick after the redirect, so wait for it rather than sampling visibility once.
  const tour = page.getByRole('dialog', { name: 'Guided tour' })
  await tour.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {})
  if (await tour.isVisible()) {
    await tour.getByRole('button', { name: 'Skip' }).click()
    await expect(tour).toBeHidden()
  }
  return account
}
