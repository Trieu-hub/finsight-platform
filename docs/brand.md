# Vernfy brand

Open [`images/logo-preview.html`](images/logo-preview.html) in a browser to see every file at every
size, on both backgrounds.

## Files

| File | Use |
|---|---|
| [`images/vernfy-mark.svg`](images/vernfy-mark.svg) | Icon only, 64×64. App icon, avatar. |
| [`images/vernfy-logo-dark.svg`](images/vernfy-logo-dark.svg) | Horizontal lockup for dark backgrounds (the app, `#0a0a0a`). |
| [`images/vernfy-logo-light.svg`](images/vernfy-logo-light.svg) | Horizontal lockup for light backgrounds (GitHub README, slides, print). |
| `web/public/favicon.svg` | The mark again, served by the SPA and referenced from `web/index.html`. Keep the two copies in step. |

## The idea

One stroke that reads three ways at once:

- the letter **V** — Vernfy;
- a **line chart that bottoms out and recovers** — the platform watches spending over time;
- a **checkmark** — Vernfy ≈ *verify*, and the product's job is telling you things are fine (or not).

The ascending arm is deliberately longer than the descending one, so the eye finishes travelling
upward rather than settling in the trough. The node at the top right is the latest data point.

## Colours

Taken from the app's existing accents, not invented — the SPA already uses emerald/teal throughout.

| Role | Dark bg | Light bg |
|---|---|---|
| Stroke start | `#10B981` emerald-500 | `#059669` emerald-600 |
| Stroke end | `#2DD4BF` teal-400 | `#0D9488` teal-600 |
| Node | `#5EEAD4` teal-300 | `#0D9488` teal-600 |
| Wordmark | `#E5E5E5` | `#0F172A` |

## Known limitation — the wordmark is live text

The lockups use an `<text>` element with a font stack, so the wordmark renders in whatever sans
the viewer has (Inter → system-ui → Segoe UI → …). That keeps the file editable, but it is **not
pixel-identical across machines**. Before using a lockup anywhere the exact letterforms matter —
print, a submitted asset, a slide deck opened on someone else's laptop — convert it to outlines:

- Inkscape: select the text → **Path → Object to Path** → save.
- Figma: paste the SVG → select the text → **Outline stroke** / flatten → export SVG.

The mark (`vernfy-mark.svg`) contains no text and has no such issue.

## What this replaced

`web/public/favicon.svg` used to be a **purple lightning-bolt placeholder** (`#863bff`) left over
from a template — it matched neither the brand nor the app's emerald palette. It was also never
actually served: `web/index.html` carried no `<link rel="icon">`, so the browser fell back to its
default. Both are fixed.

## If you want to explore other directions

These SVGs are hand-drawn geometry, not AI output. To generate alternatives to compare against,
this prompt is written for an image model (ChatGPT / Gemini / Midjourney):

> Design a minimal, modern logo mark for **Vernfy**, a personal-finance intelligence and risk
> monitoring platform for students. Flat vector, geometric, single continuous stroke with rounded
> caps, no gradients beyond a subtle emerald-to-teal transition, no text, no drop shadows, no 3D.
> The mark should read simultaneously as the letter **V**, as a line chart that dips and recovers,
> and as a checkmark. Emerald green `#10B981` to teal `#2DD4BF` on a near-black `#0A0A0A`
> background. Balanced enough to stay legible at 16×16 px. Centred, generous negative space,
> flat-design app-icon style.

Ask for several variants, then judge them at 16 px — that is where weak marks fall apart.
