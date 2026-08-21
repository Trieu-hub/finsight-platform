import { describe, expect, it } from 'vitest'

// The install prompt is all-or-nothing and fails silently: a manifest that 404s, parses wrong, or
// points at a missing icon simply means no prompt, with nothing in the console to explain it. And
// nginx answers a missing file with index.html (try_files, see web/nginx.conf), so a typo in a path
// arrives as HTML with a 200 rather than a 404. These assertions are the cheap early warning.
//
// Read through Vite (`?raw`, `import.meta.glob`) rather than node:fs on purpose: tsconfig.app.json
// deliberately gives src/ only `vite/client` types, and this test is not worth pulling Node's into
// the browser project for.
import manifestRaw from '../public/manifest.json?raw'
import indexHtml from '../index.html?raw'

const manifest = JSON.parse(manifestRaw)
const pngs = Object.keys(import.meta.glob('../public/**/*.png'))
const webmanifests = Object.keys(import.meta.glob('../public/*.webmanifest'))

const hasPublicFile = (src: string) => pngs.includes(`../public${src}`)

describe('PWA manifest', () => {
  it('is named .json, the only extension nginx has a MIME type for', () => {
    // web/nginx.conf ships no `types` block, and the nginx image's mime.types has no entry for
    // .webmanifest — it would go out as application/octet-stream. `.json` is mapped already.
    expect(manifestRaw.length).toBeGreaterThan(0)
    expect(webmanifests).toEqual([])
  })

  it('carries the members a browser requires before it will offer to install', () => {
    expect(manifest.name).toBeTruthy()
    expect(manifest.short_name).toBeTruthy()
    expect(manifest.start_url).toBe('/')
    expect(manifest.scope).toBe('/')
    // Standalone is not cosmetic here: iOS only delivers web push to a home-screen web app.
    expect(manifest.display).toBe('standalone')
    expect(manifest.theme_color).toMatch(/^#[0-9A-Fa-f]{6}$/)
    expect(manifest.background_color).toMatch(/^#[0-9A-Fa-f]{6}$/)
  })

  it('offers both icon sizes Chrome asks for, plus a maskable one', () => {
    const icons: { sizes: string; purpose?: string }[] = manifest.icons
    expect(icons.map((icon) => icon.sizes)).toEqual(
      expect.arrayContaining(['192x192', '512x512']),
    )
    expect(icons.some((icon) => icon.purpose === 'maskable')).toBe(true)
  })

  it('points every icon at a file that is really there', () => {
    for (const icon of manifest.icons as { src: string }[]) {
      expect(hasPublicFile(icon.src), `missing ${icon.src}`).toBe(true)
    }
  })

  /*
   * Screenshots are what upgrade Chromium's install prompt from a one-line bar to the rich,
   * app-store-like sheet. They are also the easiest thing in the manifest to get silently wrong:
   * a missing file, or only a `wide` entry, and the phone — where installs actually happen —
   * quietly falls back to the minimal prompt with nothing logged.
   */
  it('ships a narrow screenshot, which is the one a phone install dialog uses', () => {
    const shots: { src: string; sizes: string; form_factor?: string }[] = manifest.screenshots ?? []

    expect(shots.length).toBeGreaterThan(0)
    expect(shots.some((shot) => shot.form_factor === 'narrow')).toBe(true)
    for (const shot of shots) {
      expect(hasPublicFile(shot.src), `missing ${shot.src}`).toBe(true)
      expect(shot.sizes).toMatch(/^\d+x\d+$/)
    }
  })

  /*
   * Shortcuts are the long-press menu on an installed icon. A shortcut pointing at a route the
   * router does not know still opens — nginx serves index.html for anything — so the user lands
   * on the SPA's not-found instead of an error, and nothing anywhere reports the typo.
   */
  it('sends every shortcut to a route the app actually has', () => {
    const routes = ['/', '/transactions', '/budgets', '/wallets', '/analytics', '/import']
    const shortcuts: { name: string; url: string; icons?: { src: string }[] }[] =
      manifest.shortcuts ?? []

    expect(shortcuts.length).toBeGreaterThan(0)
    for (const shortcut of shortcuts) {
      expect(shortcut.name).toBeTruthy()
      expect(routes, `unknown route ${shortcut.url}`).toContain(shortcut.url)
      for (const icon of shortcut.icons ?? []) {
        expect(hasPublicFile(icon.src), `missing ${icon.src}`).toBe(true)
      }
    }
  })
})

describe('index.html', () => {
  it('links the manifest, so the browser ever looks for it', () => {
    expect(indexHtml).toContain('rel="manifest"')
    expect(indexHtml).toContain('href="/manifest.json"')
  })

  it('ships an apple-touch-icon, the only icon iOS reads for the home screen', () => {
    expect(indexHtml).toContain('rel="apple-touch-icon"')
    expect(hasPublicFile('/apple-touch-icon.png')).toBe(true)
  })

  it('describes the product, since #root is empty to anything that does not run JS', () => {
    // Regression: with no description, Google quoted the login form back at itself and AI
    // summaries called the site a generic login portal of no stated purpose.
    const description = indexHtml.match(/name="description"[\s\S]*?content="([^"]+)"/)?.[1]
    expect(description, 'index.html has no meta description').toBeTruthy()
    expect(description!.length).toBeGreaterThan(50)
    // Search engines cut the snippet around here; longer is wasted, not wrong.
    expect(description!.length).toBeLessThanOrEqual(160)
    expect(indexHtml).toMatch(/<title>[^<]{10,70}<\/title>/)
  })
})
