// American roulette engine — wheel geometry, the legal bet catalogue, payouts and
// settlement. See docs/games/american-roulette.md for the maths this encodes.
//
// The one invariant that governs the whole payout table: a bet covering n pockets pays
// (36 - n)/n to 1, while a fair payout would be (38 - n)/n. The shortfall of 2/n gives
// every bet the same expected value of -2/38 = -5.263% — except the basket, which pays
// 6:1 instead of 6.6:1 and so runs at -3/38 = -7.895%.

export type Pocket = string // '0' | '00' | '1' … '36'
export type Colour = 'red' | 'black' | 'green'

/** Physical clockwise order of the American wheel. Index = position on the rotor. */
export const WHEEL: readonly Pocket[] = [
  '0', '28', '9', '26', '30', '11', '7', '20', '32', '17', '5', '22', '34', '15', '3',
  '24', '36', '13', '1',
  '00', '27', '10', '25', '29', '12', '8', '19', '31', '18', '6', '21', '33', '16', '4',
  '23', '35', '14', '2',
]

export const POCKET_COUNT = WHEEL.length // 38

const RED_NUMBERS = new Set([
  1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36,
])

export function colourOf(p: Pocket): Colour {
  if (p === '0' || p === '00') return 'green'
  return RED_NUMBERS.has(Number(p)) ? 'red' : 'black'
}

export type BetType =
  | 'straight'
  | 'split'
  | 'street'
  | 'trio'
  | 'corner'
  | 'basket'
  | 'sixline'
  | 'column'
  | 'dozen'
  | 'red'
  | 'black'
  | 'odd'
  | 'even'
  | 'low'
  | 'high'

/** Payout in "x to 1". Basket is the deliberate outlier (6, not 6.6). */
export const PAYOUT: Record<BetType, number> = {
  straight: 35,
  split: 17,
  street: 11,
  trio: 11,
  corner: 8,
  basket: 6,
  sixline: 5,
  column: 2,
  dozen: 2,
  red: 1,
  black: 1,
  odd: 1,
  even: 1,
  low: 1,
  high: 1,
}

const NUMS = Array.from({ length: 36 }, (_, i) => String(i + 1))
export const ALL_POCKETS: Pocket[] = ['0', '00', ...NUMS]

const RANK = new Map(ALL_POCKETS.map((p, i) => [p, i]))
const byRank = (a: Pocket, b: Pocket) => (RANK.get(a) ?? 0) - (RANK.get(b) ?? 0)

/** Canonical id for a set of pockets — order-independent, so it doubles as the bet key. */
export function keyOf(covers: Pocket[]): string {
  return [...covers].sort(byRank).join(',')
}

export interface BetDef {
  type: BetType
  covers: Pocket[]
}

/**
 * Every legal *inside* chip position, derived from the layout geometry rather than typed
 * out by hand: 38 straights + 62 splits + 12 streets + 3 trios + 22 corners + 11 six lines
 * + 1 basket = 149. With the 12 outside bets that is the full 161-proposition catalogue.
 *
 * Layout adjacency: n and n+1 share a street (unless n is a multiple of 3, which ends one);
 * n and n+3 are side by side.
 */
const INSIDE: Map<string, BetDef> = (() => {
  const m = new Map<string, BetDef>()
  const add = (type: BetType, covers: Pocket[]) => m.set(keyOf(covers), { type, covers })
  const s = String

  ALL_POCKETS.forEach((p) => add('straight', [p]))

  for (let n = 1; n <= 36; n++) if (n % 3 !== 0) add('split', [s(n), s(n + 1)]) // within a street
  for (let n = 1; n <= 33; n++) add('split', [s(n), s(n + 3)]) // between two streets
  add('split', ['0', '00'])
  add('split', ['0', '1'])
  add('split', ['0', '2'])
  add('split', ['00', '2'])
  add('split', ['00', '3'])

  for (let n = 1; n <= 34; n += 3) add('street', [s(n), s(n + 1), s(n + 2)])

  add('trio', ['0', '1', '2'])
  add('trio', ['0', '00', '2'])
  add('trio', ['00', '2', '3'])

  for (let n = 1; n <= 32; n++) {
    if (n % 3 !== 0) add('corner', [s(n), s(n + 1), s(n + 3), s(n + 4)])
  }

  for (let n = 1; n <= 31; n += 3) {
    add('sixline', [s(n), s(n + 1), s(n + 2), s(n + 3), s(n + 4), s(n + 5)])
  }

  add('basket', ['0', '00', '1', '2', '3'])

  return m
})()

/**
 * Identify the bet a selection of pockets represents, or null if the chip position is
 * illegal (e.g. two numbers that do not touch on the layout). The UI lets the player build
 * a selection freely; this is what refuses to pay 17:1 on a made-up "split".
 */
export function classify(selection: Pocket[]): BetDef | null {
  if (selection.length === 0) return null
  return INSIDE.get(keyOf(selection)) ?? null
}

export interface OutsideDef extends BetDef {
  id: string
  /** Short label rendered on the felt. */
  label: string
}

const column = (c: number) => NUMS.filter((n) => Number(n) % 3 === c % 3)
const dozen = (d: number) => NUMS.filter((n) => Number(n) > d * 12 && Number(n) <= (d + 1) * 12)

export const COLUMNS: OutsideDef[] = [0, 1, 2].map((i) => ({
  id: `column-${i + 1}`,
  type: 'column' as const,
  covers: column(i + 1),
  label: '2:1',
}))

export const DOZENS: OutsideDef[] = [0, 1, 2].map((i) => ({
  id: `dozen-${i + 1}`,
  type: 'dozen' as const,
  covers: dozen(i),
  label: `${i * 12 + 1}-${(i + 1) * 12}`,
}))

export const EVEN_MONEY: OutsideDef[] = [
  { id: 'low', type: 'low', covers: NUMS.filter((n) => Number(n) <= 18), label: '1-18' },
  { id: 'even', type: 'even', covers: NUMS.filter((n) => Number(n) % 2 === 0), label: 'EVEN' },
  { id: 'red', type: 'red', covers: NUMS.filter((n) => colourOf(n) === 'red'), label: 'RED' },
  { id: 'black', type: 'black', covers: NUMS.filter((n) => colourOf(n) === 'black'), label: 'BLACK' },
  { id: 'odd', type: 'odd', covers: NUMS.filter((n) => Number(n) % 2 === 1), label: 'ODD' },
  { id: 'high', type: 'high', covers: NUMS.filter((n) => Number(n) >= 19), label: '19-36' },
]

export interface PlacedBet extends BetDef {
  id: string // keyOf(covers) — one chip stack per distinct position
  amount: number
}

/** Probability that a bet covering n pockets wins. */
export function probability(n: number): number {
  return n / POCKET_COUNT
}

// There is deliberately no spin() here. The outcome is drawn by the server
// (transaction-service, com.pm.transactionservice.game.Roulette) with SecureRandom, and the
// money it moves is a real transaction against the user's wallet. A client-side RNG would be
// one devtools breakpoint away from picking its own winner. Everything in this module is for
// *display*: drawing the wheel, and previewing a chip's payout and odds before it is placed.
// The server re-derives the bet type from the pockets and never trusts what is sent here.
