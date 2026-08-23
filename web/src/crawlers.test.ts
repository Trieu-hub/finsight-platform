import { describe, expect, it } from 'vitest'

// nginx answers a missing file with index.html (try_files, see web/nginx.conf), so a crawler file
// that was never written does not 404 — it arrives as a 200 full of HTML. That is exactly how this
// went unnoticed: Lighthouse read /robots.txt, got `<!doctype html>`, and reported 50 syntax errors
// in a file that did not exist. Same for /llms.txt ("missing H1", "no links" — it was the SPA
// shell). Assert the files are here and parse, because the failure mode is a silent 200.
//
// Read through Vite (`?raw`) rather than node:fs for the reason pwa.test.ts gives: src/ is typed
// with `vite/client` only.
import robotsTxt from '../public/robots.txt?raw'
import llmsTxt from '../public/llms.txt?raw'
import sitemapXml from '../public/sitemap.xml?raw'

const ORIGIN = 'https://vernfy.com'

describe('robots.txt', () => {
  it('is a robots file, not the SPA shell', () => {
    expect(robotsTxt).not.toContain('<!doctype html>')
    expect(robotsTxt).toMatch(/^User-agent:/m)
    expect(robotsTxt).toMatch(/^Allow: \/$/m)
  })

  it('keeps crawlers off the API, which is JSON behind a JWT', () => {
    expect(robotsTxt).toMatch(/^Disallow: \/api\/$/m)
  })

  it('points at a sitemap that is really shipped', () => {
    const sitemap = robotsTxt.match(/^Sitemap: (\S+)$/m)?.[1]
    expect(sitemap).toBe(`${ORIGIN}/sitemap.xml`)
    expect(sitemapXml).toContain('<urlset')
  })
})

describe('llms.txt', () => {
  it('opens with the H1 the format requires', () => {
    expect(llmsTxt).not.toContain('<!doctype html>')
    expect(llmsTxt.split('\n')[0]).toMatch(/^# \S/)
  })

  it('carries markdown links, which is the point of the file', () => {
    expect(llmsTxt.match(/\[[^\]]+\]\(https?:\/\/[^)]+\)/g)?.length ?? 0).toBeGreaterThan(0)
  })
})

describe('sitemap.xml', () => {
  it('lists only pages a signed-out visitor can actually reach', () => {
    const locs = [...sitemapXml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1])
    expect(locs).toEqual([`${ORIGIN}/`, `${ORIGIN}/login`, `${ORIGIN}/register`])
  })
})
