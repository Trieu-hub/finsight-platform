import { expect, test } from '@playwright/test'

/**
 * The security headers, checked against a running origin rather than against the config that is
 * supposed to produce them.
 *
 * Two different failures are covered, and the second is the one that matters. A missing header is
 * easy to spot in a config diff. A **CSP that is present and too strict** is not: the headers all
 * look right, and the app quietly loses a stylesheet or refuses to register its service worker,
 * on the user's machine, with the explanation only in their console. So this loads the real page
 * in a real browser and fails on any CSP violation it reports.
 *
 *   E2E_BASE_URL=https://vernfy.com npx playwright test security-headers --project=chromium
 *
 * Skipped in the ordinary suite: `vite preview` serves no headers, so there would be nothing to
 * assert — these come from Caddy (docker/caddy/Caddyfile) in front of it.
 */
const ORIGIN = process.env.E2E_BASE_URL

test.skip(!ORIGIN, 'set E2E_BASE_URL to check a deployed origin')

test('serves the security headers', async ({ request }) => {
  const response = await request.get('/')
  const headers = response.headers()

  expect(headers['content-security-policy']).toBeTruthy()
  expect(headers['x-content-type-options']).toBe('nosniff')
  expect(headers['x-frame-options']).toBe('DENY')
  expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin')
  expect(headers['permissions-policy']).toContain('geolocation=()')
  // frame-ancestors is what actually stops a modern browser framing the app; X-Frame-Options
  // above is only the fallback for older ones.
  expect(headers['content-security-policy']).toContain("frame-ancestors 'none'")
  // No wildcard sources: a CSP with `*` in it is decoration.
  expect(headers['content-security-policy']).not.toContain('*')
})

test('does not tell the world which server version it runs', async ({ request }) => {
  const response = await request.get('/')

  // Present and versioned is free reconnaissance; absent is one less thing to match against a
  // CVE list.
  expect(response.headers()['server'] ?? '').not.toMatch(/nginx\/[\d.]+/)
})

test('the CSP does not block anything this app serves', async ({ page }) => {
  // The `securitypolicyviolation` DOM event, not `page.on('console')`. The console route looks
  // like it works and silently never fires: verified by blocking the manifest outright, where
  // the console listener still reported a clean run. A listener that cannot fail is worse than
  // no listener, because it is mistaken for evidence.
  await page.addInitScript(() => {
    ;(window as unknown as { __csp: string[] }).__csp = []
    document.addEventListener('securitypolicyviolation', (event) => {
      ;(window as unknown as { __csp: string[] }).__csp.push(
        `${event.violatedDirective} blocked ${event.blockedURI || 'inline'}`,
      )
    })
  })

  await page.goto('/login')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  // The manifest and the worker are requested after first paint; give them a moment to be
  // refused, or a violation lands after the assertion and the run passes for the wrong reason.
  await page.waitForTimeout(1500)

  const violations = await page.evaluate(() => (window as unknown as { __csp: string[] }).__csp)

  // Only violations for something WE serve. Cloudflare injects its own challenge script into the
  // HTML at the edge (`window.__CF$cv$params`), the CSP refuses it, and that is neither our bug
  // nor our loss — the app never depended on it. It cannot be excluded by inspecting the event:
  // `blockedURI` is the literal string "inline" and Chromium leaves `sample` empty, so an inline
  // violation carries nothing that says whose script it was. The second assertion below closes
  // that gap from the other side instead.
  const ours = violations.filter((entry) => !entry.endsWith('blocked inline'))
  expect(ours, `CSP blocked something this app serves:\n${ours.join('\n')}`).toEqual([])

  // ...so prove separately that the only inline script on the page is the edge's. If a build ever
  // starts emitting an inline <script> of our own, this fails — and the "blocked inline" we
  // tolerate above stops being safe to tolerate.
  // Search the whole body, truncate only for the message: Cloudflare's marker sits ~150
  // characters in, so slicing first hid it and made its own script look like ours.
  const inlineScripts = await page.evaluate(() =>
    Array.from(document.querySelectorAll('script'))
      .filter((script) => !script.src)
      .map((script) => script.textContent || ''),
  )
  const notCloudflare = inlineScripts
    .filter((body) => !body.includes('__CF$cv$params'))
    .map((body) => body.slice(0, 120))
  expect(
    notCloudflare,
    `This app now ships an inline <script>, which the CSP blocks:\n${notCloudflare.join('\n')}`,
  ).toEqual([])
})
