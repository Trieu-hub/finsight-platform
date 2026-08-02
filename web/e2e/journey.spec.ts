import { expect, test, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'

/**
 * The critical journey, in a real browser against the real stack: sign up → record income → see it
 * in the history. k6's smoke test already proves the same endpoints answer over HTTP; what only a
 * browser can prove is that the SPA renders them, keeps the token, and routes between the pages.
 *
 * Every run creates its own account, so the tests never depend on seeded data and can run against
 * any freshly started stack. Text is queried in English — the default language for a browser with
 * no stored preference, which is what a clean Playwright context is.
 */

/** Unique per run: auth rejects a duplicate username OR email, and CI re-runs against a live DB. */
function newAccount() {
  const stamp = `${Date.now().toString(36)}${randomBytes(6).toString('hex')}`
  return { username: `e2e${stamp}`, email: `e2e${stamp}@vernfy.test`, password: 'E2ePass123' }
}

async function signUp(page: Page) {
  const account = newAccount()
  await page.goto('/register')
  await page.getByLabel('Username').fill(account.username)
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Password (min 8 chars)').fill(account.password)
  await page.getByRole('button', { name: 'Create account' }).click()

  // Registration signs the user straight in and lands on the dashboard.
  await expect(page).toHaveURL(/\/$/)

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

test('a new user can sign up, record income and find it in their history', async ({ page }) => {
  await signUp(page)

  await page.goto('/transactions')
  const form = page.locator('section[data-tour="tx-form"] form')
  await expect(form).toBeVisible()

  // Reaching the controls by their label is also the check that they *have* one: the fields are
  // built by a Field component whose <label> wraps the control, so a regression there fails here.
  // The selects are filled by an async load, so wait for the options before touching the form.
  const category = form.getByLabel('Category')
  await expect(category.locator('option').first()).toBeAttached()

  // INCOME on purpose: an expense must be charged to a budget the user has not created yet, so
  // income is the journey that stands alone. The category defaults to the first of the chosen type.
  await form.getByLabel('Type').selectOption('INCOME')
  await expect(category).not.toHaveValue('')
  await form.getByLabel('Amount').fill('1250000')
  const description = `salary ${Date.now()}`
  await form.getByLabel('Description').fill(description)
  await form.getByRole('button', { name: 'Add transaction' }).click()

  // The row is the proof the write reached transaction-service and came back through the gateway.
  const list = page.locator('section[data-tour="tx-list"]')
  await expect(list.getByText(description)).toBeVisible()
  await expect(list.getByRole('row').filter({ hasText: description })).toContainText('+')

  // And the dashboard, which fans out through the BFF, reflects the same write. Its recent list
  // shows the *category*, not the description, so the signed amount is what identifies the row.
  // That fan-out is also the one call that can time out at the gateway on a cold JVM (deploy.md's
  // cold-start note), so retry the whole load instead of asserting once against a page that gave up.
  await page.goto('/')
  const recent = page.locator('section[data-tour="dash-recent"]')
  await expect(async () => {
    await page.reload()
    await expect(recent.getByText('+₫1,250,000')).toBeVisible({ timeout: 8_000 })
  }).toPass({ timeout: 45_000 })
})

test('a signed-out visitor is sent to the login page', async ({ page }) => {
  await page.goto('/transactions')

  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
})

test('wrong credentials are rejected with the API error, not a blank page', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('Email').fill('nobody@vernfy.test')
  await page.getByLabel('Password').fill('WrongPassword1')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.locator('form p.text-red-400')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
})
