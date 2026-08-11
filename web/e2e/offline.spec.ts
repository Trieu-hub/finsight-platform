import { expect, test } from '@playwright/test'

/**
 * The offline cache, proven the only way that counts: a real browser, a real service worker, and
 * the network genuinely cut with `context.setOffline`.
 *
 * Unlike the other journeys here, this one needs **no backend**. `/login` is public, and the point
 * of the test is the app shell surviving without a server — so it runs against `vite preview`
 * alone. It does need the production bundle, which is what `vite preview` serves: the worker is
 * deliberately not registered under `vite dev`.
 */
test.describe('offline', () => {
  test('serves the app shell with the network cut, instead of the browser error page', async ({
    page,
    context,
  }) => {
    await page.goto('/login')

    // The worker registers on `load` and then claims the page. Waiting for it to be *controlling*
    // is the only reliable gate — a registration that has not claimed yet still leaves the next
    // navigation going straight to the network.
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, {
      timeout: 20_000,
    })

    const shellUrls = await page.evaluate(async () => {
      const cache = await caches.open('vernfy-shell-v1')
      return (await cache.keys()).map((request) => new URL(request.url).pathname)
    })
    expect(shellUrls).toContain('/')

    // Cut the network for real. Nothing below can reach the preview server.
    await context.setOffline(true)
    await page.reload()

    // Rendered from the cache: the sign-in form is present, not Chrome's ERR_INTERNET_DISCONNECTED.
    await expect(page.getByRole('button', { name: /sign in|đăng nhập/i })).toBeVisible()

    await context.setOffline(false)
  })

  /**
   * The control for the test above. Without it, that test would also pass if Chrome's own HTTP
   * cache had quietly served the page — proving nothing about this worker. With the worker torn
   * down and its caches dropped, the same offline reload must fail.
   */
  test('fails offline once the worker is gone, proving it was the worker doing the work', async ({
    page,
    context,
  }) => {
    await page.goto('/login')
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, {
      timeout: 20_000,
    })

    await page.evaluate(async () => {
      const registrations = await navigator.serviceWorker.getRegistrations()
      await Promise.all(registrations.map((registration) => registration.unregister()))
      await Promise.all((await caches.keys()).map((key) => caches.delete(key)))
    })

    await context.setOffline(true)
    await expect(page.reload()).rejects.toThrow()

    await context.setOffline(false)
  })
})
