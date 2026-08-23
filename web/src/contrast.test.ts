import { describe, expect, it } from 'vitest'

/*
 * Colour-contrast regressions, caught structurally rather than visually.
 *
 * Light mode reverses the NEUTRAL ramp (see src/index.css) but leaves the accent ramps alone, so
 * a class like `text-emerald-400` — light ink, designed for a dark surface — was landing on a
 * near-white page. Measured against the shipped stylesheet before this guard existed:
 * emerald-400 1.77:1, amber-300 1.33:1, rose-300 1.76:1 on a light card. WCAG AA wants 4.5:1.
 * Nothing failed loudly; the text was simply hard to read, and only in one theme.
 *
 * These assertions are cheap and dumb on purpose. They do not compute contrast — they enforce the
 * three rules the audit produced:
 *
 *   1. bright accent ink (steps 300/400) must be paired with a light-mode counterpart, i.e. it
 *      may only appear behind a `dark:` variant;
 *   2. `text-white` must never share a className with a `bg-neutral-*` surface, because that
 *      surface flips to near-white in light mode and the text vanishes;
 *   3. the roulette table's own palette carries no neutral step at all. Rule 2 cannot see that
 *      one — the ink lives in the component and the surface in a lookup map two hundred lines
 *      away — and that indirection is precisely how `bg-neutral-900` + `text-white` survived in
 *      the black pockets, painting white numerals on a near-white cell in light mode.
 */
const sources = Object.entries(
  import.meta.glob('./**/*.tsx', { query: '?raw', import: 'default', eager: true }),
).filter(([path]) => !path.includes('.test.')) as [string, string][]

// A physical game token keeps its own colour in both themes, so it is painted on zinc/black — a
// scale light mode does not touch — and its ink must not gain a dark: variant. See CHIP_STYLE.
const THEME_INDEPENDENT = /from-zinc-800 to-black/

const BRIGHT_INK =
  /(?<![\w:-])(?!dark:)(?:[a-z-]+:)*text-(?:emerald|rose|red|amber|sky|teal)-(?:300|400)(?![\w-])/g
// One className, however many lines and ternaries it spans.
const CLASSNAME = /className=(?:"([^"]*)"|\{`([\s\S]*?)`\}|\{'([^']*)'\})/g
const NEUTRAL_SURFACE = /bg-neutral-(?:700|800|900|950)(?![\w-])/

describe('theme contrast', () => {
  it('reads every component, so an empty glob cannot pass this file', () => {
    expect(sources.length).toBeGreaterThan(10)
  })

  it('never uses bright accent ink without a light-mode counterpart', () => {
    const offenders: string[] = []
    for (const [path, src] of sources) {
      src.split('\n').forEach((line, i) => {
        if (THEME_INDEPENDENT.test(line)) return
        for (const m of line.matchAll(BRIGHT_INK)) offenders.push(`${path}:${i + 1} ${m[0]}`)
      })
    }
    expect(offenders).toEqual([])
  })

  it('never puts white text on a neutral surface, which reverses in light mode', () => {
    const offenders: string[] = []
    for (const [path, src] of sources) {
      for (const m of src.matchAll(CLASSNAME)) {
        const classes = m[1] ?? m[2] ?? m[3] ?? ''
        if (classes.includes('text-white') && NEUTRAL_SURFACE.test(classes)) {
          offenders.push(`${path} :: ${classes.replace(/\s+/g, ' ').slice(0, 80)}`)
        }
      }
    }
    expect(offenders).toEqual([])
  })

  /*
   * The roulette table is a physical object — felt, pockets, chips — and must look the same in
   * both themes, so none of it may be painted on the neutral ramp. zinc is the scale light mode
   * leaves alone; `black` and `white` are fine too. Asserted on the two lookup maps directly,
   * because that indirection is what hid the original bug from any className-level check.
   */
  it('paints the roulette table off the reversible neutral ramp', () => {
    const roulette = sources.find(([path]) => path.endsWith('Roulette.tsx'))?.[1] ?? ''
    expect(roulette).not.toBe('')
    for (const map of ['CHIP_STYLE', 'CELL_BG'] as const) {
      const body = roulette.slice(roulette.indexOf(`${map}: Record`))
      const literal = body.slice(body.indexOf('{'), body.indexOf('}') + 1)
      expect(literal, `${map} must not use the neutral ramp`).not.toMatch(/-neutral-/)
    }
  })
})

/*
 * The remaining finding — Tailwind's neutral-500 (#737373) measuring 4.35:1 on a light card and
 * 3.78:1 on a dark one, under AA in both themes across ~46 call sites — is fixed by overriding
 * the token per theme in src/index.css, where the audited replacements and their worst-surface
 * numbers are written down. It is deliberately not asserted here: Vitest stubs CSS imports, so
 * `index.css?raw` comes back empty, and turning `test.css` on would compile the whole Tailwind
 * build on every run of every suite to check two hex values.
 */
