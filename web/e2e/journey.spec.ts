import { expect, test } from '@playwright/test'
import { signUp } from './support/account'

/**
 * The critical journey, in a real browser against the real stack: sign up → record income → see it
 * in the history. k6's smoke test already proves the same endpoints answer over HTTP; what only a
 * browser can prove is that the SPA renders them, keeps the token, and routes between the pages.
 *
 * Every run creates its own account, so the tests never depend on seeded data and can run against
 * any freshly started stack. Text is queried in English — the default language for a browser with
 * no stored preference, which is what a clean Playwright context is.
 */

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
