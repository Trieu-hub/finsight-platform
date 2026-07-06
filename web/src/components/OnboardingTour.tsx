import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useI18n } from '../i18n'

// First-run guided tour. A friendly NPC ("Vera") spotlights each real feature on the
// screen and explains it in place — walking through the Dashboard, then Transactions,
// Budgets, Wallets and Analytics, changing pages automatically as it goes.
//
// A step targets a real element by its `data-tour="<id>"` attribute. If that element
// can't be found (e.g. a page with no data yet), the step gracefully falls back to a
// centred card so the tour never breaks.
//
// Copy is localised: each step carries an i18n `key`; the text comes from tour.<key>.title
// / tour.<key>.body. NPC artwork lives in web/public/npc/ (see the README there); a missing
// image falls back to the step's emoji, so the tour works before any art is added.

type Step = {
  key: string // i18n key stem → tour.<key>.title / tour.<key>.body
  path?: string // route this step lives on; the tour navigates there if needed
  selector?: string // [data-tour="…"] element to spotlight; omit for a centred card
  image: string // /npc/<file>.png
  emoji: string // fallback shown until/if the image exists
}

const STEPS: Step[] = [
  { key: 'welcome', path: '/', image: '/npc/welcome.png', emoji: '👋' },
  { key: 'hero', path: '/', selector: '[data-tour="dash-hero"]', image: '/npc/dashboard.png', emoji: '🏠' },
  { key: 'trend', path: '/', selector: '[data-tour="dash-trend"]', image: '/npc/dashboard.png', emoji: '📈' },
  { key: 'categories', path: '/', selector: '[data-tour="dash-categories"]', image: '/npc/dashboard.png', emoji: '🍩' },
  { key: 'recent', path: '/', selector: '[data-tour="dash-recent"]', image: '/npc/dashboard.png', emoji: '🧾' },
  { key: 'budgets', path: '/', selector: '[data-tour="dash-budgets"]', image: '/npc/dashboard.png', emoji: '🎯' },
  { key: 'txForm', path: '/transactions', selector: '[data-tour="tx-form"]', image: '/npc/transactions.png', emoji: '➕' },
  { key: 'txList', path: '/transactions', selector: '[data-tour="tx-list"]', image: '/npc/transactions.png', emoji: '📋' },
  { key: 'budgetForm', path: '/budgets', selector: '[data-tour="budget-form"]', image: '/npc/budgets.png', emoji: '🎯' },
  { key: 'budgetList', path: '/budgets', selector: '[data-tour="budget-list"]', image: '/npc/budgets.png', emoji: '📊' },
  { key: 'walletForm', path: '/wallets', selector: '[data-tour="wallet-form"]', image: '/npc/wallets.png', emoji: '👛' },
  { key: 'walletList', path: '/wallets', selector: '[data-tour="wallet-list"]', image: '/npc/wallets.png', emoji: '💰' },
  { key: 'anSummary', path: '/analytics', selector: '[data-tour="analytics-summary"]', image: '/npc/analytics.png', emoji: '🧠' },
  { key: 'anForecast', path: '/analytics', selector: '[data-tour="analytics-forecast"]', image: '/npc/analytics.png', emoji: '🔮' },
  { key: 'bell', selector: '[data-tour="notif-bell"]', image: '/npc/notifications.png', emoji: '🔔' },
  { key: 'help', selector: '[data-tour="help-btn"]', image: '/npc/welcome.png', emoji: '❓' },
  { key: 'finish', image: '/npc/finish.png', emoji: '🎉' },
]

const PAD = 8 // spotlight breathing room around the target
const CARD_MAX = 380

function NpcAvatar({ image, emoji }: { image: string; emoji: string }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [image])
  if (failed) {
    return (
      <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-500/20 to-teal-500/10 text-3xl ring-1 ring-emerald-500/30">
        <span aria-hidden>{emoji}</span>
      </div>
    )
  }
  return (
    <img
      src={image}
      alt=""
      onError={() => setFailed(true)}
      className="h-14 w-14 shrink-0 rounded-xl object-contain"
    />
  )
}

type Rect = { top: number; left: number; width: number; height: number }

export default function OnboardingTour({
  open,
  onClose,
}: {
  open: boolean
  onClose: () => void
}) {
  const [i, setI] = useState(0)
  const [rect, setRect] = useState<Rect | null>(null)
  const [cardH, setCardH] = useState(0)
  const { t } = useI18n()
  const navigate = useNavigate()
  const location = useLocation()
  const elRef = useRef<HTMLElement | null>(null)
  const cardRef = useRef<HTMLDivElement | null>(null)

  const step = STEPS[i]
  const isLast = i === STEPS.length - 1
  const isFirst = i === 0

  const next = useCallback(() => setI((n) => Math.min(STEPS.length - 1, n + 1)), [])
  const back = useCallback(() => setI((n) => Math.max(0, n - 1)), [])

  // Restart from the top each time the tour opens.
  useEffect(() => {
    if (open) setI(0)
  }, [open])

  // Locate (and, if needed, navigate to) the target for the current step.
  useEffect(() => {
    if (!open) return
    const s = STEPS[i]
    let cancelled = false
    elRef.current = null
    setRect(null)

    // Move to the step's page first; the effect re-runs once the route changes.
    if (s.path && location.pathname !== s.path) {
      navigate(s.path)
      return
    }
    if (!s.selector) return // centred card

    let tries = 0
    const tick = () => {
      if (cancelled) return
      const el = document.querySelector(s.selector as string) as HTMLElement | null
      if (el) {
        elRef.current = el
        el.scrollIntoView({ block: 'center', behavior: 'auto' })
        requestAnimationFrame(() => {
          if (cancelled || !elRef.current) return
          const r = elRef.current.getBoundingClientRect()
          setRect({ top: r.top - PAD, left: r.left - PAD, width: r.width + 2 * PAD, height: r.height + 2 * PAD })
        })
        return
      }
      if (++tries > 120) return // ~4s → graceful centred fallback
      setTimeout(tick, 33)
    }
    tick()
    return () => {
      cancelled = true
    }
  }, [i, open, location.pathname, navigate])

  // Keep the spotlight glued to the element as the user scrolls / resizes.
  useEffect(() => {
    if (!open) return
    const recompute = () => {
      const el = elRef.current
      if (!el) return
      const r = el.getBoundingClientRect()
      setRect({ top: r.top - PAD, left: r.left - PAD, width: r.width + 2 * PAD, height: r.height + 2 * PAD })
    }
    window.addEventListener('scroll', recompute, true)
    window.addEventListener('resize', recompute)
    return () => {
      window.removeEventListener('scroll', recompute, true)
      window.removeEventListener('resize', recompute)
    }
  }, [open])

  // Keyboard: Esc closes, arrows navigate.
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') next()
      else if (e.key === 'ArrowLeft') back()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose, next, back])

  // Measure the callout so we can keep it fully on-screen (re-measures per step / as the
  // spotlight moves). Guards against a state loop by only updating when the height changes.
  useLayoutEffect(() => {
    if (!open) return
    const hh = cardRef.current?.offsetHeight
    if (hh) setCardH((prev) => (prev !== hh ? hh : prev))
  }, [i, rect, open])

  if (!open) return null

  // Position the callout near the spotlight, but ALWAYS fully inside the viewport so no
  // text is ever clipped. Prefer below the target, then above, then pin to a safe edge.
  // With no target (or before it's measured), centre it.
  const vw = window.innerWidth
  const vh = window.innerHeight
  const cardW = Math.min(CARD_MAX, vw - 24)
  const h = cardH || 220 // fallback until the first measurement lands
  const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi))
  let cardStyle: React.CSSProperties
  if (rect) {
    const gap = 14
    const left = clamp(rect.left + rect.width / 2 - cardW / 2, 12, vw - cardW - 12)
    const belowTop = rect.top + rect.height + gap
    const aboveTop = rect.top - gap - h
    let top: number
    if (belowTop + h <= vh - 12) top = belowTop // fits below
    else if (aboveTop >= 12) top = aboveTop // else fits above
    else top = Math.max(12, vh - h - 12) // else pin to the bottom edge, still on-screen
    cardStyle = { width: cardW, left, top, maxHeight: vh - 24, overflowY: 'auto' }
  } else {
    cardStyle = {
      width: cardW,
      left: '50%',
      top: '50%',
      transform: 'translate(-50%, -50%)',
      maxHeight: vh - 24,
      overflowY: 'auto',
    }
  }

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={t('nav.tour')}>
      {/* Click-catcher: dims when there's no spotlight, blocks interaction with the page. */}
      <div
        className={`absolute inset-0 ${rect ? '' : 'bg-black/70 backdrop-blur-sm'}`}
        onClick={(e) => e.stopPropagation()}
      />

      {/* Spotlight: a transparent box whose huge shadow dims everything around it. */}
      {rect && (
        <div
          className="pointer-events-none absolute rounded-xl ring-2 ring-emerald-400/80"
          style={{
            top: rect.top,
            left: rect.left,
            width: rect.width,
            height: rect.height,
            boxShadow: '0 0 0 9999px rgba(0,0,0,0.70)',
            transition: 'all 0.25s ease',
          }}
        />
      )}

      {/* Callout card */}
      <div
        ref={cardRef}
        className="absolute rounded-2xl border border-neutral-800 bg-neutral-900 shadow-2xl shadow-black/60"
        style={cardStyle}
      >
        <div className="flex gap-3.5 p-4">
          <NpcAvatar image={step.image} emoji={step.emoji} />
          <div className="min-w-0 flex-1">
            <p className="text-[11px] font-semibold uppercase tracking-wide text-emerald-400">
              {t('tour.guide')}
            </p>
            <h2 className="mt-0.5 text-base font-bold text-neutral-100">
              {t(`tour.${step.key}.title`)}
            </h2>
            <p className="mt-1.5 text-sm leading-relaxed text-neutral-400">
              {t(`tour.${step.key}.body`)}
            </p>
          </div>
        </div>

        <div className="flex justify-center gap-1.5 pb-3">
          {STEPS.map((_, idx) => (
            <span
              key={idx}
              className={`h-1.5 rounded-full transition-all ${
                idx === i ? 'w-5 bg-emerald-400' : 'w-1.5 bg-neutral-700'
              }`}
            />
          ))}
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-neutral-800 px-4 py-3">
          <button
            onClick={onClose}
            className="text-sm font-medium text-neutral-500 transition hover:text-neutral-300"
          >
            {t('tour.skip')}
          </button>
          <div className="flex items-center gap-2">
            <span className="mr-1 text-xs text-neutral-600">
              {i + 1}/{STEPS.length}
            </span>
            {!isFirst && (
              <button
                onClick={back}
                className="rounded-lg border border-neutral-800 px-3 py-1.5 text-sm font-medium text-neutral-300 transition hover:border-neutral-700 hover:bg-neutral-800"
              >
                {t('tour.back')}
              </button>
            )}
            <button
              onClick={isLast ? onClose : next}
              className="rounded-lg bg-emerald-600 px-4 py-1.5 text-sm font-semibold text-white shadow-lg shadow-emerald-900/40 transition hover:bg-emerald-500"
            >
              {isLast ? t('tour.getStarted') : t('tour.next')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
