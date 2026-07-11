import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n'
import redMeme from '../assets/luckyme-red.jpg'

// LuckyMe: flip a two-sided coin, biased 3:1 toward red.
//   * Red (75%)  -> show the meme image full-view, then sign the user out. Nothing else.
//   * Green (25%) -> the coin disappears, a confetti burst fires, then the mini-games
//                    interface is shown on its own (no coin UI).
// The 3D spin uses inline styles (preserve-3d / backface-visibility) so it works
// regardless of the Tailwind version's transform-3d support.

type Phase = 'idle' | 'flipping' | 'red' | 'green'

const SPIN_MS = 2200 // keep in sync with the coin transition duration
const RED_PROBABILITY = 0.75 // 3 out of 4 flips land red

// A fixed confetti palette; each piece gets a random slice of it.
const CONFETTI_COLORS = ['#34d399', '#2dd4bf', '#fbbf24', '#f472b6', '#60a5fa', '#f87171']

function Confetti() {
  // Build the pieces once so they don't reshuffle on every render.
  const pieces = useMemo(
    () =>
      Array.from({ length: 90 }, (_, i) => ({
        left: Math.random() * 100,
        delay: Math.random() * 0.6,
        duration: 2.4 + Math.random() * 1.6,
        color: CONFETTI_COLORS[i % CONFETTI_COLORS.length],
        size: 6 + Math.random() * 6,
      })),
    [],
  )
  return (
    <div className="pointer-events-none fixed inset-0 z-40 overflow-hidden" aria-hidden>
      {pieces.map((p, i) => (
        <span
          key={i}
          style={{
            position: 'absolute',
            top: 0,
            left: `${p.left}%`,
            width: p.size,
            height: p.size * 0.4,
            backgroundColor: p.color,
            borderRadius: 1,
            animation: `luckyme-confetti-fall ${p.duration}s linear ${p.delay}s forwards`,
          }}
        />
      ))}
    </div>
  )
}

export default function LuckyMe() {
  const { t } = useI18n()
  const { signOut } = useAuth()
  const navigate = useNavigate()

  const [phase, setPhase] = useState<Phase>('idle')
  const [rotation, setRotation] = useState(0) // absolute degrees, only ever increases
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])

  const flip = () => {
    if (phase === 'flipping') return
    const result: 'red' | 'green' = Math.random() < RED_PROBABILITY ? 'red' : 'green'
    setPhase('flipping')

    // Land on 0deg (red = front) or 180deg (green = back), after ~5 forward spins.
    const desired = result === 'green' ? 180 : 0
    let target = rotation + 360 * 5
    target = target - (target % 360) + desired
    if (target <= rotation) target += 360
    setRotation(target)

    // When the spin finishes, apply the outcome.
    const done = setTimeout(() => {
      setPhase(result)
      if (result === 'red') {
        // Let the user see the meme, then log them out.
        const out = setTimeout(() => {
          signOut()
          navigate('/login')
        }, 2600)
        timers.current.push(out)
      }
    }, SPIN_MS)
    timers.current.push(done)
  }

  // RED: only the meme image, then auto sign-out. Nothing else on screen.
  if (phase === 'red') {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <img
          src={redMeme}
          alt=""
          className="max-h-[80vh] w-auto max-w-full rounded-2xl shadow-2xl shadow-black/60"
        />
      </div>
    )
  }

  // GREEN: coin gone; confetti burst + the mini-games interface only.
  if (phase === 'green') {
    return (
      <>
        <Confetti />
        <section className="mx-auto max-w-3xl">
          <h2 className="mb-4 text-center text-lg font-semibold text-neutral-200">
            {t('luckyme.games.title')}
          </h2>
          <div className="flex h-64 items-center justify-center rounded-2xl border border-dashed border-neutral-800 bg-neutral-900/60 text-sm text-neutral-500">
            {t('luckyme.games.empty')}
          </div>
        </section>
      </>
    )
  }

  // IDLE / FLIPPING: the coin, centered and filling the view.
  const flipping = phase === 'flipping'
  return (
    <div className="flex min-h-[calc(100vh-11rem)] flex-col items-center justify-center gap-12 text-center">
      <header>
        <h1 className="bg-gradient-to-r from-amber-300 via-emerald-300 to-teal-300 bg-clip-text text-4xl font-black tracking-tight text-transparent sm:text-5xl">
          {t('luckyme.title')}
        </h1>
        <p className="mt-3 text-base text-neutral-300">{t('luckyme.subtitle')}</p>
        <p className="mt-1 text-sm text-neutral-500">{t('luckyme.hint')}</p>
      </header>

      {/* Coin stage */}
      <div
        style={{ perspective: '1200px' }}
        className="relative flex h-72 w-72 items-center justify-center sm:h-80 sm:w-80"
      >
        {/* Pulsing halo behind the coin */}
        <div
          style={{ animation: 'luckyme-glow 3s ease-in-out infinite' }}
          className="absolute inset-0 rounded-full bg-amber-300/25 blur-3xl"
        />

        {/* Toss layer: floats while idle, arcs upward during the flip */}
        <div
          style={{
            animation: flipping
              ? `luckyme-toss ${SPIN_MS}ms ease-in-out`
              : 'luckyme-float 3.2s ease-in-out infinite',
          }}
          className="h-full w-full"
        >
          {/* Rotator — click the coin itself to flip */}
          <button
            onClick={flip}
            disabled={flipping}
            aria-label={t('luckyme.flip')}
            style={{
              transformStyle: 'preserve-3d',
              transform: `rotateY(${rotation}deg)`,
              transition: `transform ${SPIN_MS}ms cubic-bezier(0.22, 1, 0.36, 1)`,
            }}
            className="relative h-full w-full rounded-full outline-none transition hover:brightness-110 focus-visible:brightness-110 disabled:cursor-default"
          >
            {/* Front: RED */}
            <span
              style={{
                backfaceVisibility: 'hidden',
                background:
                  'radial-gradient(circle at 35% 28%, #fca5a5, #ef4444 45%, #991b1b 100%)',
                boxShadow:
                  'inset 0 6px 18px rgba(255,255,255,0.35), inset 0 -14px 30px rgba(0,0,0,0.45), 0 26px 60px rgba(220,38,38,0.4)',
              }}
              className="absolute inset-0 flex items-center justify-center rounded-full border-[6px] border-white/15"
            >
              <span
                style={{ boxShadow: 'inset 0 2px 10px rgba(0,0,0,0.35)' }}
                className="flex h-[72%] w-[72%] items-center justify-center rounded-full border-2 border-white/25 text-7xl font-black text-white/95"
              >
                ✕
              </span>
            </span>

            {/* Back: GREEN */}
            <span
              style={{
                backfaceVisibility: 'hidden',
                transform: 'rotateY(180deg)',
                background:
                  'radial-gradient(circle at 35% 28%, #6ee7b7, #10b981 45%, #065f46 100%)',
                boxShadow:
                  'inset 0 6px 18px rgba(255,255,255,0.35), inset 0 -14px 30px rgba(0,0,0,0.45), 0 26px 60px rgba(16,185,129,0.4)',
              }}
              className="absolute inset-0 flex items-center justify-center rounded-full border-[6px] border-white/20"
            >
              <span
                style={{ boxShadow: 'inset 0 2px 10px rgba(0,0,0,0.35)' }}
                className="flex h-[72%] w-[72%] items-center justify-center rounded-full border-2 border-white/25 text-7xl font-black text-white/95"
              >
                ★
              </span>
            </span>
          </button>
        </div>
      </div>

      <button
        onClick={flip}
        disabled={flipping}
        className="rounded-2xl bg-gradient-to-r from-amber-400 via-emerald-400 to-teal-400 px-10 py-3.5 text-base font-bold text-neutral-950 shadow-xl shadow-emerald-900/30 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {flipping ? t('luckyme.flipping') : t('luckyme.flip')}
      </button>
    </div>
  )
}
