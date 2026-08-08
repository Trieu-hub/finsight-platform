import { expect, test, type Page } from '@playwright/test'
import { randomBytes } from 'node:crypto'

/**
 * Nothing may scroll the page sideways on a phone.
 *
 * This lives here rather than in the Vitest suite because jsdom has no layout engine — it cannot
 * tell you an element is 41px wider than the viewport. It is also the one class of bug the device
 * toolbar in devtools reported differently from a real handset, which is how it reached production
 * unnoticed: the header's right-hand controls did not fit under 401px, so every page could be
 * dragged sideways.
 */

function newAccount() {
  const stamp = `${Date.now().toString(36)}${randomBytes(6).toString('hex')}`
  return { username: `mob${stamp}`, email: `mob${stamp}@vernfy.test`, password: 'E2ePass123' }
}

async function signUp(page: Page) {
  const account = newAccount()
  await page.goto('/register')
  await page.getByLabel('Username').fill(account.username)
  await page.getByLabel('Email').fill(account.email)
  await page.getByLabel('Password (min 8 chars)').fill(account.password)
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page).toHaveURL(/\/$/)

  const tour = page.getByRole('dialog', { name: 'Guided tour' })
  await tour.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {})
  if (await tour.isVisible()) {
    await tour.getByRole('button', { name: 'Skip' }).click()
    await expect(tour).toBeHidden()
  }
}

const ROUTES = ['/', '/transactions', '/budgets', '/wallets', '/analytics', '/import']

test('no page can be scrolled sideways at phone widths', async ({ page }) => {
  await signUp(page)

  // 320 is the narrowest width still worth supporting; 360 is the common Android.
  for (const width of [320, 360]) {
    await page.setViewportSize({ width, height: 720 })

    for (const route of ROUTES) {
      // Not networkidle: the bell holds an SSE stream open, so the network is never idle.
      await page.goto(route, { waitUntil: 'domcontentloaded' })
      // By role, not by tag: pages carry their own <header> too, and the app bar is the banner.
      await expect(page.getByRole('banner')).toBeVisible()

      const overflow = await page.evaluate(
        () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
      )
      expect(overflow, `${route} overflows by ${overflow}px at ${width}px`).toBeLessThanOrEqual(0)

      // scrollWidth alone can be clipped into looking fine, so also prove the gesture does nothing.
      await page.mouse.wheel(400, 0)
      expect(await page.evaluate(() => window.scrollX), `${route} scrolled at ${width}px`).toBe(0)
    }
  }
})
