import { expect, test, type Page } from '@playwright/test'
import { signUp } from './support/account'

/**
 * The money loop, in a real browser: wallet → budget → expense → the numbers every other screen
 * derives from it. `journey.spec.ts` deliberately records INCOME, because income stands alone; an
 * expense is the harder half — it must be charged to a budget the user picks, it moves a wallet
 * balance transaction-service owns, and it reaches budget-service **over Kafka**, so the tally on
 * the Budgets page arrives after the request that caused it has already returned 200.
 *
 * That last part is why this belongs in a browser and not in the Vitest suite: a mocked network
 * answers instantly and in order, which is exactly the property the real path does not have.
 */

const WALLET_OPENING = 5_000_000
const BUDGET_LIMIT = 600_000
const EXPENSE = 200_000

/** en-US currency formatting, the same `Intl` call `lib/format.money` makes. */
function vnd(amount: number) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'VND' }).format(amount)
}

async function createWallet(page: Page, name: string, opening: number) {
  await page.goto('/wallets')
  const form = page.locator('section[data-tour="wallet-form"] form')
  await expect(form).toBeVisible()
  await form.getByLabel('Name').fill(name)
  await form.getByLabel('Opening balance').fill(String(opening))
  await form.getByRole('button', { name: 'Add wallet' }).click()

  const list = page.locator('section[data-tour="wallet-list"]')
  await expect(list.getByText(name)).toBeVisible()
}

/**
 * Creates a MONTHLY budget on whichever expense category the form defaults to, and returns that
 * category's id so the caller can charge an expense to the *same* category — the budget only
 * matches a transaction when the category, period and currency all line up.
 */
async function createBudget(page: Page, name: string, limit: number) {
  await page.goto('/budgets')
  const form = page.locator('section[data-tour="budget-form"] form')
  // The form is replaced by a "create a wallet first" card until a wallet exists, so its presence
  // is itself the assertion that the previous step landed.
  await expect(form).toBeVisible()

  const category = form.getByLabel('Category')
  await expect(category.locator('option').first()).toBeAttached()
  const categoryId = await category.inputValue()

  await form.getByLabel('Name').fill(name)
  await form.getByLabel('Limit amount').fill(String(limit))
  await form.getByRole('button', { name: 'Add budget' }).click()

  const list = page.locator('section[data-tour="budget-list"]')
  await expect(list.getByText(name)).toBeVisible()
  return categoryId
}

/** Fills the expense form and submits it. Assumes exactly one wallet, and one matching budget. */
async function recordExpense(page: Page, categoryId: string, amount: number, description: string) {
  await page.goto('/transactions')
  const form = page.locator('section[data-tour="tx-form"] form')
  await expect(form).toBeVisible()

  await form.getByLabel('Type').selectOption('EXPENSE')
  const category = form.getByLabel('Category')
  await expect(category.locator('option').first()).toBeAttached()
  await category.selectOption(categoryId)

  // The Budget field renders a select only when the category has a budget for this period —
  // otherwise it is the amber "create one first" note. Waiting for the select is the check that
  // the budget written a moment ago is visible to this form.
  await expect(form.getByLabel('Budget')).toBeVisible()

  // Index 0 is "None": the wallet has to be chosen explicitly, or nothing is charged.
  await form.getByLabel('Wallet').selectOption({ index: 1 })
  await form.getByLabel('Amount').fill(String(amount))
  await form.getByLabel('Description').fill(description)
  await form.getByRole('button', { name: 'Add transaction' }).click()
}

test('an expense moves the wallet, the budget tally and the dashboard together', async ({
  page,
}) => {
  // Three screens plus an asynchronous tally; the default 60s is not enough on a cold stack.
  test.setTimeout(150_000)

  await signUp(page)
  await createWallet(page, 'E2E Cash', WALLET_OPENING)
  const categoryId = await createBudget(page, 'E2E Food', BUDGET_LIMIT)

  const description = `lunch ${Date.now()}`
  await recordExpense(page, categoryId, EXPENSE, description)

  // 1. The row itself — proof the write reached transaction-service through the gateway.
  const list = page.locator('section[data-tour="tx-list"]')
  await expect(list.getByRole('row').filter({ hasText: description })).toContainText(
    `-${vnd(EXPENSE)}`,
  )

  // 2. The wallet balance, which transaction-service owns and updates in the same transaction.
  await page.goto('/wallets')
  await expect(page.locator('section[data-tour="wallet-list"]')).toContainText(
    vnd(WALLET_OPENING - EXPENSE),
  )

  // 3. The budget tally, which arrives over Kafka: transaction-service's outbox → relay →
  //    budget-service's consumer → the read model this page queries. Nothing about the request
  //    that created the expense waited for any of it, so poll instead of asserting once. A failure
  //    here is a broken event path, not a slow one — 60s is far past the relay's normal latency.
  await page.goto('/budgets')
  const budgets = page.locator('section[data-tour="budget-list"]')
  await expect(async () => {
    await page.reload()
    await expect(budgets.getByText(`${vnd(EXPENSE)} / ${vnd(BUDGET_LIMIT)}`)).toBeVisible({
      timeout: 5_000,
    })
  }).toPass({ timeout: 60_000 })

  // 4. And the dashboard, which reads the same budget through the BFF fan-out rather than
  //    budget-service directly — a different path to the same number.
  await page.goto('/')
  await expect(page.locator('section[data-tour="dash-budgets"]')).toContainText('E2E Food')
})

test('an expense past the limit warns at once, before the tally catches up', async ({ page }) => {
  test.setTimeout(150_000)

  await signUp(page)
  await createWallet(page, 'E2E Cash', WALLET_OPENING)
  const categoryId = await createBudget(page, 'E2E Tight', BUDGET_LIMIT)

  const over = BUDGET_LIMIT + 100_000
  await recordExpense(page, categoryId, over, `splurge ${Date.now()}`)

  // The warning is computed client-side precisely so it does not wait for Kafka. It auto-dismisses
  // after 7s, so this assertion is also the check that it appears promptly.
  const alert = page.getByRole('alert')
  await expect(alert).toContainText('Budget exceeded')
  await expect(alert).toContainText(`${vnd(over)} of ${vnd(BUDGET_LIMIT)}`)

  // Then the server-side tally agrees: the budget is marked over once the event has landed.
  await page.goto('/budgets')
  const budgets = page.locator('section[data-tour="budget-list"]')
  await expect(async () => {
    await page.reload()
    await expect(budgets.getByText('Over budget')).toBeVisible({ timeout: 5_000 })
  }).toPass({ timeout: 60_000 })
})

test('an expense in a category with no budget is refused, not silently recorded', async ({
  page,
}) => {
  await signUp(page)
  await createWallet(page, 'E2E Cash', WALLET_OPENING)

  await page.goto('/transactions')
  const form = page.locator('section[data-tour="tx-form"] form')
  await form.getByLabel('Type').selectOption('EXPENSE')
  await expect(form.getByLabel('Category').locator('option').first()).toBeAttached()

  // No budget exists at all, so every expense category is unbudgeted: the Budget field is the note,
  // not a select. This guard is what keeps one expense from being counted against several budgets.
  await expect(form.getByLabel('Budget')).toHaveCount(0)
  await expect(form).toContainText('This category has no budget for this period')

  await form.getByLabel('Wallet').selectOption({ index: 1 })
  await form.getByLabel('Amount').fill('50000')

  // The guard is on the button itself, not only in the submit handler: a filled-in, otherwise
  // valid expense still cannot be sent while no budget matches. Asserting `toBeDisabled` rather
  // than clicking is the point — a click would pass just as well against a form that quietly
  // did nothing, which is the failure mode this test exists to tell apart.
  await expect(form.getByRole('button', { name: 'Add transaction' })).toBeDisabled()

  // And nothing was written: the history stays empty and the wallet keeps its opening balance.
  await expect(page.locator('section[data-tour="tx-list"]')).toContainText(
    'No transactions this month.',
  )
  await page.goto('/wallets')
  await expect(page.locator('section[data-tour="wallet-list"]')).toContainText(
    vnd(WALLET_OPENING),
  )
})
