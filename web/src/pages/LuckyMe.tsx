import { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { useI18n } from '../i18n'

// LuckyMe: flip a two-sided coin.
//   * Red  -> the user is signed out immediately.
//   * Green -> reveal the mini-games panel (games themselves come later).
// The 3D spin uses inline styles (preserve-3d / backface-visibility) so it works
// regardless of the Tailwind version's transform-3d support.

type Phase = 'idle' | 'flipping' | 'red' | 'green'

const SPIN_MS = 2200 // keep in sync with the coin transition duration

export default function LuckyMe() {
  const { t } = useI18n()
  const { signOut } = useAuth()
  const navigate = useNavigate()

  const [phase, setPhase] = useState<Phase>('idle')
  const [rotation, setRotation] = useState(0) // absolute degrees, only ever increases
  const timers = useRef<ReturnType<typeof setTimeout>[]>([])

  const flip = () => {
    if (phase === 'flipping') return
    const result: 'red' | 'green' = Math.random() < 0.5 ? 'red' : 'green'
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
        // Let the user read the "red" message, then log them out.
        const out = setTimeout(() => {
          signOut()
          navigate('/login')
        }, 1500)
        timers.current.push(out)
      }
    }, SPIN_MS)
    timers.current.push(done)
  }

  const showResultMsg = phase === 'red' || phase === 'green'

  return (
    <div className="mx-auto max-w-md">
      <header className="mb-8 text-center">
        <h1 className="bg-gradient-to-r from-emerald-400 to-teal-400 bg-clip-text text-2xl font-extrabold tracking-tight text-transparent">
          {t('luckyme.title')}
        </h1>
        <p className="mt-2 text-sm text-neutral-400">{t('luckyme.subtitle')}</p>
        <p className="mt-1 text-xs text-neutral-600">{t('luckyme.hint')}</p>
      </header>

      {/* Coin */}
      <div className="flex flex-col items-center gap-7">
        <div style={{ perspective: '900px' }} className="h-44 w-44">
          <div
            style={{
              transformStyle: 'preserve-3d',
              transform: `rotateY(${rotation}deg)`,
              transition: `transform ${SPIN_MS}ms cubic-bezier(0.22, 1, 0.36, 1)`,
            }}
            className="relative h-full w-full"
          >
            {/* Front: RED */}
            <div
              style={{ backfaceVisibility: 'hidden' }}
              className="absolute inset-0 flex items-center justify-center rounded-full border-4 border-red-300/40 bg-gradient-to-br from-red-500 to-rose-700 text-5xl shadow-2xl shadow-red-900/40"
            >
              <span aria-hidden>✕</span>
            </div>
            {/* Back: GREEN */}
            <div
              style={{ backfaceVisibility: 'hidden', transform: 'rotateY(180deg)' }}
              className="absolute inset-0 flex items-center justify-center rounded-full border-4 border-emerald-200/40 bg-gradient-to-br from-emerald-400 to-teal-600 text-5xl shadow-2xl shadow-emerald-900/40"
            >
              <span aria-hidden>★</span>
            </div>
          </div>
        </div>

        {/* Result message */}
        {showResultMsg && (
          <div className="text-center">
            <p
              className={`text-lg font-bold ${
                phase === 'red' ? 'text-red-400' : 'text-emerald-400'
              }`}
            >
              {phase === 'red' ? t('luckyme.red.title') : t('luckyme.green.title')}
            </p>
            {phase === 'red' && (
              <p className="mt-1 text-sm text-neutral-400">{t('luckyme.red.desc')}</p>
            )}
          </div>
        )}

        {/* Flip button — hidden once we land on red (user is on their way out) */}
        {phase !== 'red' && (
          <button
            onClick={flip}
            disabled={phase === 'flipping'}
            className="rounded-xl bg-gradient-to-r from-emerald-500 to-teal-500 px-6 py-2.5 text-sm font-semibold text-neutral-950 shadow-lg shadow-emerald-900/30 transition hover:from-emerald-400 hover:to-teal-400 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {phase === 'flipping'
              ? t('luckyme.flipping')
              : phase === 'green'
                ? t('luckyme.again')
                : t('luckyme.flip')}
          </button>
        )}
      </div>

      {/* Mini-games panel — appears on green. Empty for now; games added later. */}
      {phase === 'green' && (
        <section className="mt-10 rounded-2xl border border-neutral-800 bg-neutral-900/60 p-6">
          <h2 className="text-sm font-semibold text-neutral-200">
            {t('luckyme.games.title')}
          </h2>
          <div className="mt-4 flex h-32 items-center justify-center rounded-xl border border-dashed border-neutral-800 text-sm text-neutral-500">
            {t('luckyme.games.empty')}
          </div>
        </section>
      )}
    </div>
  )
}
