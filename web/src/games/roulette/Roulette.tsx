import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { errorMessage } from '../../api/client'
import {
  rouletteSpin,
  rouletteStatus,
  type GameBan,
  type GameStatus,
  type SpinResult,
} from '../../api/endpoints'
import { useI18n } from '../../i18n'
import { money } from '../../lib/format'
import {
  COLUMNS,
  DOZENS,
  EVEN_MONEY,
  PAYOUT,
  POCKET_COUNT,
  WHEEL,
  classify,
  colourOf,
  keyOf,
  probability,
  type BetDef,
  type PlacedBet,
  type Pocket,
} from './engine'

// American roulette played with the user's actual wallet balance.
//
// The server owns the money and the outcome: this component sends the chips (which pockets each
// one covers), and the server spins, derives each bet's type and payout from its pockets, settles
// the round, writes ONE net transaction against the wallet, and decides whether the round left the
// player in enough debt to be locked out. Everything below is presentation — the wheel is animated
// to the pocket the server returned, not to one chosen here.

const CHIPS = [10_000, 50_000, 100_000, 500_000]
const SPIN_MS = 5200
const SECTOR = 360 / POCKET_COUNT // 9.4737°

const CHIP_STYLE: Record<number, string> = {
  10_000: 'from-neutral-200 to-neutral-400 text-neutral-900',
  50_000: 'from-red-400 to-red-600 text-white',
  100_000: 'from-emerald-400 to-emerald-600 text-white',
  500_000: 'from-neutral-800 to-black text-amber-300 ring-1 ring-amber-400/60',
}

/** Compact chip face: 10.000 → "10K", 500.000 → "500K", 1.000.000 → "1M". */
function chipFace(value: number): string {
  if (value >= 1_000_000) return `${value / 1_000_000}M`
  if (value >= 1_000) return `${value / 1_000}K`
  return String(value)
}

// --- Wheel ------------------------------------------------------------------

const CX = 150
const CY = 150
const R_OUTER = 138
const R_INNER = 96

/** Point on the rim at `deg` clockwise from 12 o'clock. */
function pt(deg: number, r: number): string {
  const a = (deg * Math.PI) / 180
  return `${(CX + r * Math.sin(a)).toFixed(3)} ${(CY - r * Math.cos(a)).toFixed(3)}`
}

const POCKET_FILL: Record<string, string> = {
  red: '#dc2626',
  black: '#171717',
  green: '#059669',
}

function Wheel({ rotation, ballRotation }: { rotation: number; ballRotation: number }) {
  const sectors = useMemo(
    () =>
      WHEEL.map((p, i) => {
        const a0 = i * SECTOR - SECTOR / 2
        const a1 = i * SECTOR + SECTOR / 2
        return {
          p,
          i,
          d: `M ${pt(a0, R_OUTER)} A ${R_OUTER} ${R_OUTER} 0 0 1 ${pt(a1, R_OUTER)} L ${pt(a1, R_INNER)} A ${R_INNER} ${R_INNER} 0 0 0 ${pt(a0, R_INNER)} Z`,
          fill: POCKET_FILL[colourOf(p)],
        }
      }),
    [],
  )

  const ease = `transform ${SPIN_MS}ms cubic-bezier(0.16, 0.9, 0.2, 1)`

  return (
    <svg viewBox="0 0 300 300" className="h-full w-full drop-shadow-2xl">
      <circle cx={CX} cy={CY} r={148} fill="#1c1917" stroke="#a16207" strokeWidth="4" />
      <circle cx={CX} cy={CY} r={142} fill="none" stroke="#78350f" strokeWidth="2" />

      {/* Rotor: spun so the winning pocket comes to rest under the pointer */}
      <g style={{ transform: `rotate(${rotation}deg)`, transformOrigin: '150px 150px', transition: ease }}>
        {sectors.map(({ p, i, d, fill }) => (
          <g key={p}>
            <path d={d} fill={fill} stroke="#d4d4d4" strokeWidth="0.4" />
            <g style={{ transform: `rotate(${i * SECTOR}deg)`, transformOrigin: '150px 150px' }}>
              <text
                x={CX}
                y={CY - R_INNER - 26}
                textAnchor="middle"
                fontSize="10"
                fontWeight="700"
                fill="#fafafa"
                fontFamily="ui-monospace, monospace"
              >
                {p}
              </text>
            </g>
          </g>
        ))}
        <circle cx={CX} cy={CY} r={R_INNER} fill="#292524" stroke="#a16207" strokeWidth="2" />
        <circle cx={CX} cy={CY} r={54} fill="#1c1917" stroke="#a16207" strokeWidth="1.5" />
        <path d="M150 100 L150 200 M100 150 L200 150" stroke="#a16207" strokeWidth="3" strokeLinecap="round" />
        <circle cx={CX} cy={CY} r={14} fill="#eab308" />
      </g>

      {/* Ball: counter-rotates, comes to rest at 12 o'clock over the winning pocket */}
      <g style={{ transform: `rotate(${ballRotation}deg)`, transformOrigin: '150px 150px', transition: ease }}>
        <circle cx={CX} cy={CY - 108} r={7} fill="#fafafa" />
        <circle cx={CX - 2} cy={CY - 110} r={2.4} fill="#e5e5e5" />
      </g>

      <path d="M150 4 L141 22 L159 22 Z" fill="#fbbf24" stroke="#78350f" strokeWidth="1" />
    </svg>
  )
}

// --- Ban screen -------------------------------------------------------------

function formatRemaining(seconds: number): string {
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (d > 0) return `${d}d ${h}h ${m}m`
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

function Banned({
  ban,
  currency,
  onBack,
  onExpired,
}: {
  ban: GameBan
  currency: string
  onBack: () => void
  onExpired: () => void
}) {
  const { t } = useI18n()
  const [remaining, setRemaining] = useState(ban.secondsRemaining)

  // Count down from the server's number rather than from the client clock: a user who winds their
  // system clock forward gains nothing, because the next spin is refused by the server anyway.
  useEffect(() => {
    setRemaining(ban.secondsRemaining)
    const id = setInterval(() => {
      setRemaining((r) => {
        if (r <= 1) {
          clearInterval(id)
          onExpired()
          return 0
        }
        return r - 1
      })
    }, 1000)
    return () => clearInterval(id)
  }, [ban.secondsRemaining, ban.bannedUntil, onExpired])

  return (
    <section className="mx-auto flex min-h-[calc(100vh-11rem)] max-w-lg flex-col items-center justify-center text-center">
      <div className="mb-6 flex h-24 w-24 items-center justify-center rounded-full bg-red-500/15 ring-2 ring-red-500/40">
        <svg viewBox="0 0 24 24" fill="none" stroke="#f87171" strokeWidth="1.8" className="h-12 w-12">
          <rect x="4" y="10" width="16" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
        </svg>
      </div>

      <h2 className="text-3xl font-black text-red-400">{t('roulette.ban.title')}</h2>
      <p className="mt-3 text-neutral-300">
        {t('roulette.ban.desc', { debt: money(ban.debt, currency) })}
      </p>

      <div className="mt-8 rounded-2xl border border-neutral-800 bg-neutral-900 px-8 py-5">
        <p className="text-xs uppercase tracking-wide text-neutral-500">{t('roulette.ban.unlocksIn')}</p>
        <p className="mt-1 font-mono text-3xl font-black text-amber-300">{formatRemaining(remaining)}</p>
        <p className="mt-2 text-xs text-neutral-600">{t(`roulette.ban.tier.${ban.tier}`)}</p>
      </div>

      <p className="mt-6 max-w-sm text-sm text-neutral-500">{t('roulette.ban.advice')}</p>

      <button
        onClick={onBack}
        className="mt-8 rounded-xl border border-neutral-700 px-5 py-2.5 text-sm font-semibold text-neutral-300 transition hover:bg-neutral-800"
      >
        ← {t('roulette.back')}
      </button>
    </section>
  )
}

// --- Table ------------------------------------------------------------------

const CELL_BG: Record<string, string> = {
  red: 'bg-red-700 hover:bg-red-600',
  black: 'bg-neutral-900 hover:bg-neutral-800',
  green: 'bg-emerald-700 hover:bg-emerald-600',
}

export default function Roulette({ onBack }: { onBack: () => void }) {
  const { t } = useI18n()

  const [status, setStatus] = useState<GameStatus | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [chip, setChip] = useState(CHIPS[0])
  const [selection, setSelection] = useState<Pocket[]>([])
  const [bets, setBets] = useState<PlacedBet[]>([])
  const [spinning, setSpinning] = useState(false)
  const [rotation, setRotation] = useState(0)
  const [ballRotation, setBallRotation] = useState(0)
  const [outcome, setOutcome] = useState<SpinResult | null>(null)
  const [betError, setBetError] = useState<string | null>(null)
  const [history, setHistory] = useState<Pocket[]>([])
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async () => {
    try {
      setStatus(await rouletteStatus())
      setLoadError(null)
    } catch (e) {
      setLoadError(errorMessage(e))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  const staked = bets.reduce((s, b) => s + b.amount, 0)
  const pending = classify(selection)
  const covered = useMemo(() => new Set(bets.flatMap((b) => b.covers)), [bets])

  // Money the player has left to commit this round: the wallet, minus what is already on the felt.
  const available = status ? status.maxStake - staked : 0

  const addBet = (def: BetDef) => {
    if (spinning || chip > available) return
    setBetError(null)
    const id = keyOf(def.covers)
    setBets((bs) => {
      const at = bs.findIndex((b) => b.id === id)
      if (at === -1) return [...bs, { ...def, id, amount: chip }]
      const next = [...bs]
      next[at] = { ...next[at], amount: next[at].amount + chip }
      return next
    })
  }

  const removeBet = (id: string) => {
    if (spinning) return
    setBets((bs) => bs.filter((b) => b.id !== id))
  }

  const clearBets = () => {
    if (spinning) return
    setBets([])
    setSelection([])
  }

  const togglePocket = (p: Pocket) => {
    if (spinning) return
    setSelection((s) => (s.includes(p) ? s.filter((x) => x !== p) : [...s, p]))
  }

  const placeSelection = () => {
    if (!pending) return
    addBet(pending)
    setSelection([])
  }

  const doSpin = async () => {
    if (spinning || bets.length === 0 || !status) return
    setBetError(null)
    setSpinning(true)
    setOutcome(null)

    let spin: SpinResult
    try {
      spin = await rouletteSpin(
        status.walletId,
        bets.map((b) => ({ pockets: b.covers, amount: b.amount })),
      )
    } catch (e) {
      setSpinning(false)
      setBetError(errorMessage(e))
      load() // the refusal may be a fresh ban — re-read the authoritative state
      return
    }

    // Land the server's pocket under the 12 o'clock pointer: rotate so that
    // (rotation + index * SECTOR) ≡ 0 (mod 360), after 6 extra full turns.
    const base = rotation + 360 * 6
    const target = base - ((((base + spin.pocketIndex * SECTOR) % 360) + 360) % 360)
    setRotation(target)
    setBallRotation((b) => b - 360 * 9)

    timer.current = setTimeout(() => {
      setOutcome(spin)
      setHistory((h) => [spin.result as Pocket, ...h].slice(0, 14))
      setBets([])
      setSpinning(false)
      load() // re-read balance, stake limit and ban from the server rather than guessing

      if (spin.ban) {
        // Let the player see the pocket that did it, then let the ban screen take over
        // (it renders once `outcome` is cleared and `status.ban` is set).
        timer.current = setTimeout(() => setOutcome(null), 2800)
      }
    }, SPIN_MS)
  }

  if (loadError) {
    return (
      <section className="mx-auto flex min-h-[50vh] max-w-md flex-col items-center justify-center gap-4 text-center">
        <p className="text-sm text-red-400">{loadError}</p>
        <p className="text-sm text-neutral-500">{t('roulette.needWallet')}</p>
        <button
          onClick={onBack}
          className="rounded-xl border border-neutral-700 px-5 py-2.5 text-sm font-semibold text-neutral-300 transition hover:bg-neutral-800"
        >
          ← {t('roulette.back')}
        </button>
      </section>
    )
  }

  if (!status) {
    return (
      <p className="py-24 text-center text-sm text-neutral-500">{t('roulette.loading')}</p>
    )
  }

  // A live ban takes over the whole screen; nothing else is reachable while it lasts.
  if (status.ban && !spinning && !outcome) {
    return (
      <Banned ban={status.ban} currency={status.currency} onBack={onBack} onExpired={load} />
    )
  }

  const numberCell = (p: Pocket) => {
    const selected = selection.includes(p)
    return (
      <button
        key={p}
        onClick={() => togglePocket(p)}
        disabled={spinning}
        className={`relative flex items-center justify-center rounded py-2 font-mono text-sm font-bold text-white transition disabled:cursor-not-allowed ${CELL_BG[colourOf(p)]} ${
          selected ? 'ring-2 ring-amber-400' : covered.has(p) ? 'ring-1 ring-amber-400/40' : ''
        }`}
      >
        {p}
      </button>
    )
  }

  const outsideCell = (def: (typeof COLUMNS)[number], extra = '') => {
    const id = keyOf(def.covers)
    const amount = bets.find((b) => b.id === id)?.amount ?? 0
    return (
      <button
        key={def.id}
        onClick={() => addBet(def)}
        disabled={spinning || chip > available}
        className={`relative flex items-center justify-center rounded border border-neutral-700 bg-emerald-950/60 px-2 py-2 text-xs font-bold text-neutral-200 transition hover:bg-emerald-900/60 disabled:cursor-not-allowed disabled:opacity-50 ${extra}`}
      >
        {def.label}
        {amount > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-amber-400 px-1 text-[10px] font-black text-neutral-900">
            {chipFace(amount)}
          </span>
        )}
      </button>
    )
  }

  // Horizontal felt: 3 rows × 12 columns, top row is 3,6,9…36.
  const rows = [3, 2, 1].map((r) => Array.from({ length: 12 }, (_, c) => String(c * 3 + r)))
  const canBet = status.canPlay && !spinning

  return (
    <section className="mx-auto max-w-6xl">
      <header className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <button
            onClick={onBack}
            className="rounded-lg border border-neutral-800 px-3 py-1.5 text-sm text-neutral-400 transition hover:bg-neutral-800 hover:text-neutral-100"
          >
            ← {t('roulette.back')}
          </button>
          <h2 className="bg-gradient-to-r from-amber-300 to-red-400 bg-clip-text text-2xl font-black text-transparent">
            {t('roulette.title')}
          </h2>
        </div>
        <div className="rounded-xl border border-neutral-800 bg-neutral-900 px-4 py-2 text-right">
          <span className="block text-xs text-neutral-500">{t('roulette.walletBalance')}</span>
          <span
            className={`font-mono text-lg font-bold ${status.balance < 0 ? 'text-red-400' : 'text-amber-300'}`}
          >
            {money(status.balance, status.currency)}
          </span>
        </div>
      </header>

      {!status.canPlay && !status.ban && (
        <p className="mb-4 rounded-xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-300">
          {t('roulette.brokeWarning')}
        </p>
      )}

      <div className="grid gap-6 lg:grid-cols-[320px_1fr]">
        {/* Wheel + result + history */}
        <div className="flex flex-col gap-4">
          <div className="h-80 w-full">
            <Wheel rotation={rotation} ballRotation={ballRotation} />
          </div>

          <div className="rounded-xl border border-neutral-800 bg-neutral-900/60 p-4 text-center">
            {!outcome ? (
              <p className="text-sm text-neutral-500">
                {spinning ? t('roulette.spinning') : t('roulette.awaiting')}
              </p>
            ) : (
              <>
                <div
                  className={`mx-auto flex h-14 w-14 items-center justify-center rounded-full font-mono text-xl font-black text-white ${
                    outcome.colour === 'red'
                      ? 'bg-red-600'
                      : outcome.colour === 'black'
                        ? 'bg-neutral-800 ring-1 ring-neutral-600'
                        : 'bg-emerald-600'
                  }`}
                >
                  {outcome.result}
                </div>
                <p
                  className={`mt-2 text-sm font-bold ${
                    outcome.net > 0 ? 'text-emerald-400' : outcome.net < 0 ? 'text-red-400' : 'text-neutral-400'
                  }`}
                >
                  {outcome.net > 0
                    ? t('roulette.netWin', { amount: money(outcome.net, outcome.currency) })
                    : outcome.net < 0
                      ? t('roulette.netLoss', { amount: money(-outcome.net, outcome.currency) })
                      : t('roulette.push')}
                </p>
                <p className="mt-1 text-xs text-neutral-600">{t('roulette.recorded')}</p>
              </>
            )}
          </div>

          {history.length > 0 && (
            <div>
              <p className="mb-2 text-xs uppercase tracking-wide text-neutral-500">
                {t('roulette.history')}
              </p>
              <div className="flex flex-wrap gap-1.5">
                {history.map((p, i) => (
                  <span
                    key={i}
                    className={`flex h-7 w-7 items-center justify-center rounded font-mono text-xs font-bold text-white ${
                      colourOf(p) === 'red'
                        ? 'bg-red-700'
                        : colourOf(p) === 'black'
                          ? 'bg-neutral-800'
                          : 'bg-emerald-700'
                    }`}
                  >
                    {p}
                  </span>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Felt + controls */}
        <div className="flex flex-col gap-4">
          <div className="rounded-2xl border border-emerald-900/60 bg-emerald-950/40 p-3">
            <div className="flex gap-1">
              <div className="flex w-12 flex-col gap-1">
                {(['0', '00'] as Pocket[]).map((p) => (
                  <button
                    key={p}
                    onClick={() => togglePocket(p)}
                    disabled={spinning}
                    className={`flex flex-1 items-center justify-center rounded bg-emerald-700 py-3 font-mono text-sm font-bold text-white transition hover:bg-emerald-600 disabled:cursor-not-allowed ${
                      selection.includes(p)
                        ? 'ring-2 ring-amber-400'
                        : covered.has(p)
                          ? 'ring-1 ring-amber-400/40'
                          : ''
                    }`}
                  >
                    {p}
                  </button>
                ))}
              </div>

              <div className="grid flex-1 grid-cols-12 grid-rows-3 gap-1">
                {rows.flat().map((p) => numberCell(p))}
              </div>

              <div className="flex w-12 flex-col gap-1">
                {COLUMNS.map((c) => outsideCell(c, 'flex-1'))}
              </div>
            </div>

            <div className="mt-1 flex gap-1">
              <div className="w-12 shrink-0" />
              <div className="grid flex-1 grid-cols-3 gap-1">{DOZENS.map((d) => outsideCell(d))}</div>
              <div className="w-12 shrink-0" />
            </div>

            <div className="mt-1 flex gap-1">
              <div className="w-12 shrink-0" />
              <div className="grid flex-1 grid-cols-6 gap-1">
                {EVEN_MONEY.map((e) => outsideCell(e))}
              </div>
              <div className="w-12 shrink-0" />
            </div>
          </div>

          {/* Chips */}
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-xs uppercase tracking-wide text-neutral-500">{t('roulette.chip')}</span>
            {CHIPS.map((c) => (
              <button
                key={c}
                onClick={() => setChip(c)}
                disabled={spinning}
                className={`h-11 w-11 rounded-full bg-gradient-to-br text-xs font-black shadow-lg transition disabled:opacity-50 ${CHIP_STYLE[c]} ${
                  chip === c
                    ? 'scale-110 ring-2 ring-amber-300 ring-offset-2 ring-offset-neutral-950'
                    : 'hover:scale-105'
                }`}
              >
                {chipFace(c)}
              </button>
            ))}
          </div>

          {/* Selection → bet */}
          <div className="rounded-xl border border-neutral-800 bg-neutral-900/60 p-4">
            {selection.length === 0 ? (
              <p className="text-sm text-neutral-500">{t('roulette.selectionHint')}</p>
            ) : pending ? (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="text-sm">
                  <span className="font-semibold text-neutral-100">
                    {t(`roulette.bet.${pending.type}`)}
                  </span>{' '}
                  <span className="font-mono text-neutral-400">{pending.covers.join(' · ')}</span>
                  <p className="mt-0.5 text-xs text-neutral-500">
                    {t('roulette.pays', { odds: String(PAYOUT[pending.type]) })} ·{' '}
                    {t('roulette.chance', {
                      pct: (probability(pending.covers.length) * 100).toFixed(2),
                    })}
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => setSelection([])}
                    className="rounded-lg border border-neutral-700 px-3 py-2 text-sm text-neutral-400 transition hover:bg-neutral-800"
                  >
                    {t('roulette.clearSelection')}
                  </button>
                  <button
                    onClick={placeSelection}
                    disabled={!canBet || chip > available}
                    className="rounded-lg bg-emerald-500 px-4 py-2 text-sm font-bold text-neutral-950 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {t('roulette.placeBet', { amount: chipFace(chip) })}
                  </button>
                </div>
              </div>
            ) : (
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-sm text-red-400">{t('roulette.invalidSelection')}</p>
                <button
                  onClick={() => setSelection([])}
                  className="rounded-lg border border-neutral-700 px-3 py-2 text-sm text-neutral-400 transition hover:bg-neutral-800"
                >
                  {t('roulette.clearSelection')}
                </button>
              </div>
            )}
          </div>

          {/* Active bets */}
          <div className="rounded-xl border border-neutral-800 bg-neutral-900/60 p-4">
            <div className="mb-3 flex items-center justify-between">
              <p className="text-xs uppercase tracking-wide text-neutral-500">{t('roulette.bets')}</p>
              <p className="text-sm text-neutral-400">
                {t('roulette.totalStake')}{' '}
                <span className="font-mono font-bold text-amber-300">
                  {money(staked, status.currency)}
                </span>
              </p>
            </div>

            {bets.length === 0 ? (
              <p className="text-sm text-neutral-600">{t('roulette.noBets')}</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {bets.map((b) => (
                  <li
                    key={b.id}
                    className="flex items-center justify-between gap-3 rounded-lg bg-neutral-800/60 px-3 py-2 text-sm"
                  >
                    <span className="min-w-0 flex-1 truncate">
                      <span className="font-semibold text-neutral-100">
                        {t(`roulette.bet.${b.type}`)}
                      </span>{' '}
                      <span className="font-mono text-xs text-neutral-500">{b.covers.join(',')}</span>
                    </span>
                    <span className="font-mono font-bold text-amber-300">{chipFace(b.amount)}</span>
                    <button
                      onClick={() => removeBet(b.id)}
                      disabled={spinning}
                      aria-label={t('roulette.remove')}
                      className="text-neutral-500 transition hover:text-red-400 disabled:opacity-40"
                    >
                      ✕
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {betError && <p className="mt-3 text-sm text-red-400">{betError}</p>}

            <div className="mt-4 flex gap-3">
              <button
                onClick={clearBets}
                disabled={spinning || bets.length === 0}
                className="rounded-xl border border-neutral-700 px-4 py-3 text-sm font-semibold text-neutral-300 transition hover:bg-neutral-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {t('roulette.clearBets')}
              </button>
              <button
                onClick={doSpin}
                disabled={!canBet || bets.length === 0}
                className="flex-1 rounded-xl bg-gradient-to-r from-amber-400 to-red-500 py-3 text-base font-black text-neutral-950 shadow-lg transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {spinning ? t('roulette.spinning') : t('roulette.spin')}
              </button>
            </div>
          </div>

          <p className="text-center text-xs text-neutral-600">{t('roulette.edge')}</p>
        </div>
      </div>
    </section>
  )
}
