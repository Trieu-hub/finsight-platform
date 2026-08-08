// Renders the brand mark to the PNG sizes a PWA install needs. Uses the Playwright chromium that
// web/ already depends on, so nothing new is added to package.json just to rasterise four files.
//
// Run from web/:  node make-icons.mjs
import { chromium } from '@playwright/test'
import { readFileSync } from 'node:fs'

// A white plate, so the light-background palette from docs/brand.md is the correct mark: the
// dark-background one (emerald-500 -> teal-400) is tuned for #0a0a0a and goes weak on white.
const PLATE = '#FFFFFF'
const MARK = '../docs/images/vernfy-mark-light.svg'
const OUT = 'public'
const svg = readFileSync(MARK, 'utf8')

// inset = how much of the canvas the mark occupies. A maskable icon is cropped to a circle of
// ~80% diameter by the launcher, so its mark has to sit well inside that.
const ICONS = [
  { file: 'icon-192.png', size: 192, inset: 0.72 },
  { file: 'icon-512.png', size: 512, inset: 0.72 },
  { file: 'icon-maskable-512.png', size: 512, inset: 0.56 },
  { file: 'apple-touch-icon.png', size: 180, inset: 0.72 },
]

const browser = await chromium.launch()
const page = await browser.newPage()

for (const { file, size, inset } of ICONS) {
  const box = Math.round(size * inset)
  await page.setViewportSize({ width: size, height: size })
  await page.setContent(`<!doctype html><html><head><style>
      html,body{margin:0;padding:0}
      .plate{width:${size}px;height:${size}px;background:${PLATE};display:flex;align-items:center;justify-content:center}
      .mark{width:${box}px;height:${box}px}
      svg{width:100%;height:100%;display:block}
    </style></head><body><div class="plate"><div class="mark">${svg}</div></div></body></html>`)
  await page.screenshot({ path: `${OUT}/${file}` })
  console.log(`${file}  ${size}x${size}  mark ${box}px`)
}

await browser.close()
