import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n'
import redMeme from '../assets/luckyme-red.jpg'

// LuckyMe: flip a two-sided coin, biased 3:1 toward red — styled after the
// "Gamble with your Friends" coin toss: a real 3D coin (two faces + a solid
// rim built from segments) that is tossed up, scaled toward the viewer and
// spun about a tilted axis before decelerating onto a face.
//   * Red (75%)  -> KO face; show the meme full-view, then sign the user out.
//   * Green (25%) -> gamepad face; confetti burst, then the mini-games interface.
// Admins are exempt from the bias and always land green — the house never fires
// itself. This follows the live JWT role, so promoting a user to admin (and their
// re-login) flips them to 100% green with no extra wiring.

type Phase = 'idle' | 'flipping' | 'red' | 'green'

const SPIN_MS = 2600 // keep in sync with the coin transition + toss durations
const RED_PROBABILITY = 0.75 // 3 out of 4 flips land red
const SPINS = 7 // full turns per flip before it lands

// Coin geometry (px). The rim is a cylinder approximated by SEGMENTS thin planes.
const R = 110 // coin radius -> 220px diameter
const THICK = 22 // coin thickness (rim height)
const SEGMENTS = 44
const FACE = R * 2

const CONFETTI_COLORS = ['#34d399', '#2dd4bf', '#fbbf24', '#f472b6', '#60a5fa', '#f87171']

function Confetti() {
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

// The KO face (red) — dead eyes, angry brows, little pink nose.
function KoFace() {
  return (
    <svg viewBox="0 0 100 100" className="h-[62%] w-[62%]" aria-hidden>
      <g stroke="#111827" strokeWidth="9" strokeLinecap="round">
        <line x1="20" y1="30" x2="42" y2="40" />
        <line x1="80" y1="30" x2="58" y2="40" />
      </g>
      <circle cx="35" cy="56" r="14" fill="#fff" />
      <circle cx="65" cy="56" r="14" fill="#fff" />
      <g stroke="#111827" strokeWidth="6" strokeLinecap="round">
        <line x1="29" y1="50" x2="41" y2="62" />
        <line x1="41" y1="50" x2="29" y2="62" />
        <line x1="59" y1="50" x2="71" y2="62" />
        <line x1="71" y1="50" x2="59" y2="62" />
      </g>
      <ellipse cx="50" cy="76" rx="8" ry="6" fill="#f9a8d4" />
    </svg>
  )
}

// --- Mini-game cards (art only; none of the games are implemented yet) -------

// Roulette wheel: alternating red/black pockets, one green zero, ball on the rim.
function RouletteArt() {
  const pockets = Array.from({ length: 16 }, (_, i) => i)
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden>
      <circle cx="50" cy="50" r="46" fill="#1c1917" stroke="#a16207" strokeWidth="3" />
      {pockets.map((i) => (
        <path
          key={i}
          d="M50 50 L50 7 A43 43 0 0 1 66.5 10.3 Z"
          fill={i === 0 ? '#059669' : i % 2 ? '#dc2626' : '#171717'}
          transform={`rotate(${i * 22.5} 50 50)`}
        />
      ))}
      <circle cx="50" cy="50" r="20" fill="#292524" stroke="#a16207" strokeWidth="2.5" />
      <circle cx="50" cy="50" r="5" fill="#eab308" />
      <circle cx="50" cy="14" r="4" fill="#fafafa" />
    </svg>
  )
}

// Blackjack: an ace and a face-down card, fanned.
function BlackjackArt() {
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden>
      <g transform="rotate(-14 50 55)">
        <rect x="20" y="22" width="42" height="60" rx="6" fill="#1e3a8a" stroke="#93c5fd" strokeWidth="2" />
        <path d="M28 30h26M28 38h26M28 46h26M28 54h26M28 62h26M28 70h26" stroke="#3b82f6" strokeWidth="2" />
      </g>
      <g transform="rotate(10 60 55)">
        <rect x="42" y="20" width="42" height="60" rx="6" fill="#fafafa" stroke="#d4d4d4" strokeWidth="2" />
        <text x="49" y="36" fontSize="15" fontWeight="700" fill="#171717" fontFamily="sans-serif">A</text>
        <path d="M63 44l9 12h-18z" fill="#dc2626" />
        <path d="M63 68l-6-8h12z" fill="#dc2626" />
        <text x="70" y="76" fontSize="15" fontWeight="700" fill="#171717" fontFamily="sans-serif">A</text>
      </g>
    </svg>
  )
}

// Duck race: a rubber duck on water, finish flag ahead.
function DuckRaceArt() {
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden>
      <path d="M0 70h100v30H0z" fill="#0c4a6e" />
      <path d="M0 70q12 -6 25 0t25 0t25 0t25 0" stroke="#38bdf8" strokeWidth="3" fill="none" />
      <path d="M0 82q12 -6 25 0t25 0t25 0t25 0" stroke="#0ea5e9" strokeWidth="3" fill="none" />
      <g transform="translate(-4 0)">
        <ellipse cx="44" cy="62" rx="26" ry="15" fill="#facc15" />
        <circle cx="60" cy="40" r="13" fill="#fde047" />
        <circle cx="64" cy="37" r="2.6" fill="#171717" />
        <path d="M72 41h13l-5 6h-8z" fill="#f97316" />
      </g>
      <path d="M84 16v50" stroke="#a3a3a3" strokeWidth="3" />
      <path d="M84 16h16v13H84z" fill="#fafafa" />
      <path d="M84 16h8v6.5h-8zM92 22.5h8V29h-8z" fill="#171717" />
    </svg>
  )
}

// Two dice: one red (lose), one green (win).
function DiceArt() {
  const pip = (cx: number, cy: number, key: string) => (
    <circle key={key} cx={cx} cy={cy} r="4" fill="#fafafa" />
  )
  return (
    <svg viewBox="0 0 100 100" className="h-full w-full" aria-hidden>
      <g transform="rotate(-12 34 56)">
        <rect x="10" y="34" width="46" height="46" rx="10" fill="#dc2626" stroke="#fca5a5" strokeWidth="2" />
        {[
          [22, 46],
          [44, 46],
          [33, 57],
          [22, 68],
          [44, 68],
        ].map(([x, y], i) => pip(x, y, `r${i}`))}
      </g>
      <g transform="rotate(9 70 42)">
        <rect x="48" y="16" width="42" height="42" rx="9" fill="#059669" stroke="#6ee7b7" strokeWidth="2" />
        {[
          [59, 27],
          [79, 27],
          [59, 47],
          [79, 47],
        ].map(([x, y], i) => pip(x, y, `g${i}`))}
      </g>
    </svg>
  )
}

// The gamepad face (green) — neon-glow controller = "mini games".
function GamepadFace() {
  return (
    <svg
      viewBox="0 0 100 100"
      className="h-[58%] w-[58%]"
      style={{ filter: 'drop-shadow(0 0 7px rgba(220,255,220,0.9))' }}
      aria-hidden
    >
      <g fill="none" stroke="#fff" strokeWidth="5.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M33 40h34a17 17 0 0 1 16 22l-3 10a9 9 0 0 1-16 2l-4-8H40l-4 8a9 9 0 0 1-16-2l-3-10a17 17 0 0 1 16-22Z" />
        <path d="M29 55h12M35 49v12" />
        <circle cx="64" cy="52" r="2.6" fill="#fff" stroke="none" />
        <circle cx="73" cy="59" r="2.6" fill="#fff" stroke="none" />
      </g>
    </svg>
  )
}

// Left to right, in the order the user asked for. `key` doubles as the i18n suffix.
const GAMES = [
  {
    key: 'roulette',
    render: () => <RouletteArt />,
    art: 'bg-gradient-to-br from-red-950/60 to-neutral-950',
    glow: 'hover:shadow-red-900/30',
  },
  {
    key: 'blackjack',
    render: () => <BlackjackArt />,
    art: 'bg-gradient-to-br from-emerald-950/60 to-neutral-950',
    glow: 'hover:shadow-emerald-900/30',
  },
  {
    key: 'duckrace',
    render: () => <DuckRaceArt />,
    art: 'bg-gradient-to-br from-sky-950/60 to-neutral-950',
    glow: 'hover:shadow-sky-900/30',
  },
  {
    key: 'dice',
    render: () => <DiceArt />,
    art: 'bg-gradient-to-br from-amber-950/60 to-neutral-950',
    glow: 'hover:shadow-amber-900/30',
  },
] as const

export default function LuckyMe() {
  const { t } = useI18n()
  const { signOut, isAdmin } = useAuth()
  const navigate = useNavigate()

  const [phase, setPhase] = useState<Phase>('idle')
  const [rotation, setRotation] = useState(0) // absolute degrees, only ever increases
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])

  // Pre-build the rim segments once. Each segment is a thin plane placed tangent
  // to the coin's edge, together forming the cylindrical side.
  const rim = useMemo(() => {
    const segH = (2 * Math.PI * R) / SEGMENTS + 1.5 // slight overlap hides seams
    return Array.from({ length: SEGMENTS }, (_, i) => {
      const angle = (360 / SEGMENTS) * i
      return { angle, segH }
    })
  }, [])

  const flip = () => {
    if (phase === 'flipping') return
    const result: 'red' | 'green' =
      !isAdmin && Math.random() < RED_PROBABILITY ? 'red' : 'green'
    setPhase('flipping')

    // Land on 0deg (red = front) or 180deg (green = back), after SPINS turns.
    const desired = result === 'green' ? 180 : 0
    let target = rotation + 360 * SPINS
    target = target - (target % 360) + desired
    if (target <= rotation) target += 360
    setRotation(target)

    const done = setTimeout(() => {
      setPhase(result)
      if (result === 'red') {
        const out = setTimeout(() => {
          signOut()
          navigate('/login')
        }, 2600)
        timers.current.push(out)
      }
    }, SPIN_MS)
    timers.current.push(done)
  }

  // RED: only the meme image, then auto sign-out.
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
        <section>
          <header className="mb-8 text-center">
            <h2 className="bg-gradient-to-r from-emerald-300 via-teal-300 to-sky-300 bg-clip-text text-3xl font-black tracking-tight text-transparent sm:text-4xl">
              {t('luckyme.games.title')}
            </h2>
            <p className="mt-2 text-sm text-neutral-400">{t('luckyme.games.subtitle')}</p>
          </header>

          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {GAMES.map((g) => (
              <article
                key={g.key}
                className={`group flex flex-col overflow-hidden rounded-2xl border border-neutral-800 bg-neutral-900/60 transition hover:-translate-y-1 hover:border-neutral-700 hover:shadow-2xl ${g.glow}`}
              >
                <div className={`flex h-40 items-center justify-center p-6 ${g.art}`}>
                  <div className="h-28 w-28 transition-transform duration-300 group-hover:scale-110">
                    {g.render()}
                  </div>
                </div>
                <div className="flex flex-1 flex-col gap-2 border-t border-neutral-800 p-4">
                  <h3 className="font-semibold text-neutral-100">{t(`luckyme.game.${g.key}.name`)}</h3>
                  <p className="flex-1 text-sm leading-relaxed text-neutral-400">
                    {t(`luckyme.game.${g.key}.desc`)}
                  </p>
                  <span className="mt-1 inline-flex w-fit rounded-full border border-neutral-700 bg-neutral-800/80 px-2.5 py-1 text-xs font-medium text-neutral-400">
                    {t('luckyme.games.soon')}
                  </span>
                </div>
              </article>
            ))}
          </div>
        </section>
      </>
    )
  }

  // IDLE / FLIPPING: the 3D coin, centered and filling the view.
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
        style={{ perspective: '1100px' }}
        className="relative flex h-72 w-72 items-center justify-center"
      >
        {/* Pulsing halo behind the coin */}
        <div
          style={{ animation: 'luckyme-glow 3s ease-in-out infinite' }}
          className="absolute h-56 w-56 rounded-full bg-amber-300/25 blur-3xl"
        />

        {/* Toss layer: floats while idle, arcs up + scales during the flip */}
        <div
          style={{
            transformStyle: 'preserve-3d',
            animation: flipping
              ? `luckyme-toss ${SPIN_MS}ms ease-in-out`
              : 'luckyme-float 3.2s ease-in-out infinite',
          }}
        >
          {/* Tilt layer: gives the toss its diagonal, tumbling axis */}
          <div
            style={{
              transformStyle: 'preserve-3d',
              transform: 'rotateX(14deg) rotateZ(-16deg)',
            }}
          >
            {/* Spin layer — click the coin to flip */}
            <button
              onClick={flip}
              disabled={flipping}
              aria-label={t('luckyme.flip')}
              style={{
                transformStyle: 'preserve-3d',
                transform: `rotateY(${rotation}deg)`,
                transition: `transform ${SPIN_MS}ms cubic-bezier(0.2, 0.9, 0.3, 1)`,
                width: FACE,
                height: FACE,
              }}
              className="relative cursor-pointer border-0 bg-transparent p-0 outline-none disabled:cursor-default"
            >
              {/* Front face: RED (KO) */}
              <span
                style={{
                  backfaceVisibility: 'hidden',
                  transform: `translateZ(${THICK / 2}px)`,
                  background:
                    'radial-gradient(circle at 35% 28%, #f87171, #ef4444 45%, #991b1b 100%)',
                  boxShadow: 'inset 0 6px 20px rgba(255,255,255,0.3), inset 0 -16px 34px rgba(0,0,0,0.45)',
                }}
                className="absolute inset-0 flex items-center justify-center rounded-full border-4 border-white/15"
              >
                <KoFace />
              </span>

              {/* Back face: GREEN (gamepad) */}
              <span
                style={{
                  backfaceVisibility: 'hidden',
                  transform: `rotateY(180deg) translateZ(${THICK / 2}px)`,
                  background:
                    'radial-gradient(circle at 35% 28%, #6ee7b7, #10b981 45%, #065f46 100%)',
                  boxShadow: 'inset 0 6px 20px rgba(255,255,255,0.3), inset 0 -16px 34px rgba(0,0,0,0.45)',
                }}
                className="absolute inset-0 flex items-center justify-center rounded-full border-4 border-white/20"
              >
                <GamepadFace />
              </span>

              {/* Rim: the coin's thick edge, so it reads as a real 3D coin mid-flip */}
              {rim.map(({ angle, segH }, i) => (
                <span
                  key={i}
                  style={{
                    position: 'absolute',
                    left: '50%',
                    top: '50%',
                    width: THICK,
                    height: segH,
                    marginLeft: -THICK / 2,
                    marginTop: -segH / 2,
                    transform: `rotateZ(${angle}deg) translateX(${R}px) rotateY(90deg)`,
                    background: 'linear-gradient(90deg, #b91c1c 0%, #f59e0b 50%, #047857 100%)',
                  }}
                />
              ))}
            </button>
          </div>
        </div>
      </div>

      {/* The coin itself is the button — this is just live status text. */}
      <p className="text-base font-semibold text-neutral-300">
        {flipping ? t('luckyme.flipping') : t('luckyme.flip')}
      </p>
    </div>
  )
}
