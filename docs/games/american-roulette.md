# American Roulette — Reference for the LuckyMe mini-game

Everything needed to implement, verify and reason about a double-zero (American) roulette
game: wheel and table geometry, the full bet catalogue with exact probabilities and expected
values, house-edge derivations, wheel physics, RNG and provably-fair construction, betting
systems and why they fail, simulation results, and the data structures for the implementation.

Throughout, one **unit** = one currency unit staked. Payouts are quoted as **x:1** meaning
"win x units *in addition to* getting your stake back". A spin outcome is a random variable
`X ∈ {0, 00, 1, …, 36}`, uniform over **38** pockets.

---

## 1. Wheel structure

### 1.1 Pockets

38 pockets: `0`, `00`, and `1`–`36`.

| Group | Count | Colour |
|---|---|---|
| `0`, `00` | 2 | Green |
| Red numbers | 18 | Red |
| Black numbers | 18 | Black |
| **Total** | **38** | |

**Red:** 1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
**Black:** 2, 4, 6, 8, 10, 11, 13, 15, 17, 20, 22, 24, 26, 28, 29, 31, 33, 35

The colour rule is not arbitrary — it is a parity rule that flips every 8–10 numbers:

```
n ∈ 1–10  or 19–28  →  odd = RED,   even = BLACK
n ∈ 11–18 or 29–36  →  odd = BLACK, even = RED
```

This construction guarantees exactly 18 red and 18 black, and that each colour is spread
evenly across low/high and odd/even, so no *outside* bet correlates with another beyond the
unavoidable overlap.

### 1.2 Number ordering on the wheel

American (double-zero) wheel, reading **clockwise** from `0`:

```
0 – 28 –  9 – 26 – 30 – 11 –  7 – 20 – 32 – 17 –  5 – 22 – 34 – 15 –  3 – 24 – 36 – 13 –  1
00 – 27 – 10 – 25 – 29 – 12 –  8 – 19 – 31 – 18 –  6 – 21 – 33 – 16 –  4 – 23 – 35 – 14 –  2
   (…and back to 0)
```

Read as one cycle of 38, index 0…37. `0` sits at index 0, `00` at index 19 — diametrically
opposite.

```
                      0
              2               28
         14                        9
     35                                26
   23                                     30
  4                                         11
 16                                          7
21                                            20
 6                                           32
 18                                          17
   31                                       5
     19                                   22
        8                              34
           12                       15
              29    25    10    27    3
                          00        24  36  13  1
        (schematic — 38 pockets, 0 and 00 opposite each other)
```

### 1.3 Why the wheel is arranged this way

Four constraints are satisfied simultaneously by the sequence above:

1. **Colours strictly alternate** red/black around the entire rim (the two greens break the
   cycle at the two ends of the `0`–`00` diameter). Verify: 28-B, 9-R, 26-B, 30-R, 11-B, 7-R, …
2. **Consecutive numbers sit opposite each other.** Pocket at index *i* and index *i+19* are
   *n* and *n±1*: 28↔27, 9↔10, 26↔25, 30↔29, 11↔12, 7↔8, 20↔19, 32↔31, 17↔18, 5↔6, 22↔21,
   34↔33, 15↔16, 3↔4, 24↔23, 36↔35, 13↔14, 1↔2. This is the American wheel's signature
   property (the European single-zero wheel does *not* do this).
3. **Low (1–18) and high (19–36) are interleaved** so that no contiguous arc of the wheel is
   dominated by one half of the layout.
4. **Odd/even alternate** in most of the sequence.

The purpose is *decorrelation between wheel geometry and table geometry*. A player who could
predict the ball's landing **sector** (a contiguous arc of pockets) must not thereby be able
to bet a single table proposition. Because red/black, odd/even and high/low all alternate
around the rim, any arc of *k* consecutive pockets is close to colour- and parity-balanced,
so sector prediction gives no edge on any outside bet — it only helps on inside bets, and
only if the prediction is accurate to a few pockets.

> Caveat worth knowing: the American arrangement is *worse* at this than the European one.
> The American wheel has noticeable clusters (e.g. `5-22-34-15-3-24-36-13-1` on one arc is
> heavily odd/low-biased in places), which is one reason wheel-clocking historically targeted
> American wheels.

---

## 2. Table layout

```
        ┌───────────┬───────────┐
        │     0     │    00     │           ← the two greens (top box)
┌───────┼───┬───┬───┼───────────┤
│       │ 1 │ 2 │ 3 │  2 to 1   │  ← column 3 bet (right end of each row)
│ 1st   ├───┼───┼───┤           │
│ 12    │ 4 │ 5 │ 6 │  2 to 1   │  ← column 2
│       ├───┼───┼───┤           │
│       │ 7 │ 8 │ 9 │  2 to 1   │  ← column 1
│       ├───┼───┼───┤───────────┤
│       │10 │11 │12 │
├───────┼───┼───┼───┤   The "2 to 1" boxes sit at the END of the
│ 2nd   │13 │14 │15 │   three long columns; the diagram compresses
│ 12    │16 │17 │18 │   them for space. Real layout:
│       │19 │20 │21 │
│       │22 │23 │24 │      col1 = 1,4,7,…,34
├───────┼───┼───┼───┤      col2 = 2,5,8,…,35
│ 3rd   │25 │26 │27 │      col3 = 3,6,9,…,36
│ 12    │28 │29 │30 │
│       │31 │32 │33 │
│       │34 │35 │36 │
└───────┴───┴───┴───┘
        ┌──────┬──────┬─────┬───────┬─────┬───────┐
        │ 1-18 │ EVEN │ RED │ BLACK │ ODD │ 19-36 │   ← the six even-money bets
        └──────┴──────┴─────┴───────┴─────┴───────┘
```

The grid is 12 rows × 3 columns. Chips are placed on **lines, corners and intersections**;
the position of the chip *is* the bet:

| Chip position | Bet | Covers |
|---|---|---|
| Centre of a number cell | **Straight** | 1 number |
| Line between two adjacent cells | **Split** | 2 numbers |
| Outer edge of a row | **Street** | the 3 numbers in that row |
| Intersection of 4 cells | **Corner** | 4 numbers |
| Outer edge, on the line between two rows | **Six line** | 6 numbers (2 rows) |
| Corner of `0`/`00` with `1`,`2`,`3` | **Trio** | 3 numbers incl. a green |
| Outer corner of the `0`/`00` box | **Basket** (top line) | 0, 00, 1, 2, 3 |
| `2 to 1` box at column end | **Column** | 12 numbers |
| `1st 12` / `2nd 12` / `3rd 12` | **Dozen** | 12 numbers |
| Bottom strip | **Red/Black, Odd/Even, 1-18/19-36** | 18 numbers |

**Inside bets** = anything inside the number grid (straight → six line, plus trio/basket).
**Outside bets** = columns, dozens, and the six even-money propositions.

### 2.1 Minimums and maximums

These are *casino policy*, not mathematics, but a faithful implementation needs them:

- **Table minimum** (e.g. \$5, \$10, \$25) applies **separately** to inside and outside:
  - Outside: *each individual* outside bet must meet the minimum. A \$10 table = \$10 on red,
    not \$5 on red + \$5 on even.
  - Inside: the minimum is **aggregate**. On a \$10 table you may spread \$10 across ten \$1
    straight-up chips.
- **Table maximum** is normally quoted per bet type, and is scaled so the casino's exposure
  per spin is bounded. A common structure with a \$5 minimum:

| Bet | Payout | Typical max | Max casino exposure |
|---|---|---|---|
| Straight | 35:1 | \$100 | \$3,500 |
| Split | 17:1 | \$200 | \$3,400 |
| Street / Trio | 11:1 | \$300 | \$3,300 |
| Corner | 8:1 | \$400 | \$3,200 |
| Six line | 5:1 | \$600 | \$3,000 |
| Column / Dozen | 2:1 | \$2,000 | \$4,000 |
| Even money | 1:1 | \$5,000 | \$5,000 |

The pattern `max_payout ≈ constant` is deliberate: exposure is roughly flat across bet types.
The even-money maximum is the one that **kills the Martingale** (see §7.1).

---

## 3. All bet types

Let *n* = count of covered numbers, *p* = n/38 = probability of winning, and *a* = payout odds
(a:1). For a 1-unit stake, profit is `+a` with probability *p* and `−1` with probability `1−p`:

```
EV = a·p − 1·(1−p) = a·(n/38) − (38−n)/38 = (a·n − 38 + n) / 38
```

The **fair** payout (EV = 0) would be `a* = (38 − n)/n`. Every American roulette payout is
instead `a = (36 − n)/n`. The shortfall is therefore constant:

```
a* − a = (38 − n)/n − (36 − n)/n = 2/n
```

Losing `2/n` on a bet that wins with probability `n/38` costs exactly

```
EV = − (2/n) · (n/38) = − 2/38 = − 1/19 = − 5.263157…%
```

**for every bet on the layout except the basket.** That is the single most important identity
in the game: *the payouts are all short by exactly two "phantom" pockets — the two greens.*

### 3.1 Complete bet table

Exact values; percentages rounded to 4 d.p. `EV` is per 1 unit staked.

| Bet | n | Covers | Payout | p(win) | p(win) % | EV (exact) | EV % | House edge | RTP | SD/unit |
|---|---|---|---|---|---|---|---|---|---|---|
| **Straight** | 1 | any single number incl. 0 or 00 | 35:1 | 1/38 | 2.6316% | −2/38 | −5.2632% | 5.26% | 94.74% | 5.7626 |
| **Split** | 2 | 2 adjacent numbers (incl. 0-00) | 17:1 | 2/38 | 5.2632% | −2/38 | −5.2632% | 5.26% | 94.74% | 4.0193 |
| **Street** | 3 | a row of 3 (e.g. 7-8-9) | 11:1 | 3/38 | 7.8947% | −2/38 | −5.2632% | 5.26% | 94.74% | 3.2358 |
| **Trio** | 3 | 0-1-2, 0-00-2, or 00-2-3 | 11:1 | 3/38 | 7.8947% | −2/38 | −5.2632% | 5.26% | 94.74% | 3.2358 |
| **Corner** | 4 | 4 numbers forming a square | 8:1 | 4/38 | 10.5263% | −2/38 | −5.2632% | 5.26% | 94.74% | 2.7620 |
| **Basket** | 5 | **0, 00, 1, 2, 3** | **6:1** | 5/38 | 13.1579% | **−3/38** | **−7.8947%** | **7.89%** | **92.11%** | 2.3662 |
| **Six line** | 6 | 2 adjacent rows | 5:1 | 6/38 | 15.7895% | −2/38 | −5.2632% | 5.26% | 94.74% | 2.1878 |
| **Column** | 12 | 1-4-7…34 / 2-5-8…35 / 3-6-9…36 | 2:1 | 12/38 | 31.5789% | −2/38 | −5.2632% | 5.26% | 94.74% | 1.3945 |
| **Dozen** | 12 | 1-12 / 13-24 / 25-36 | 2:1 | 12/38 | 31.5789% | −2/38 | −5.2632% | 5.26% | 94.74% | 1.3945 |
| **Red / Black** | 18 | the 18 reds / 18 blacks | 1:1 | 18/38 | 47.3684% | −2/38 | −5.2632% | 5.26% | 94.74% | 0.9986 |
| **Odd / Even** | 18 | 1,3,5…35 / 2,4,6…36 | 1:1 | 18/38 | 47.3684% | −2/38 | −5.2632% | 5.26% | 94.74% | 0.9986 |
| **Low / High** | 18 | 1–18 / 19–36 | 1:1 | 18/38 | 47.3684% | −2/38 | −5.2632% | 5.26% | 94.74% | 0.9986 |

> **`0` and `00` are neither red nor black, neither odd nor even, neither low nor high.** All
> six even-money bets lose on a green. That *is* the house edge — there is no other mechanism.

### 3.2 The basket (top line) — the one bad bet

Five numbers should pay `(38−5)/5 = 6.6:1`. It pays **6:1**. The shortfall is `0.6`, not `2/5 = 0.4`:

```
EV = 6·(5/38) − (33/38) = (30 − 33)/38 = −3/38 = −7.8947%
```

**Never offer it as the "obvious" 5-number bet without labelling the edge.** It is 50% worse
than every other bet on the table. It exists only on American wheels.

### 3.3 Worked examples

**Straight-up, \$10 on 17:**
```
win:  p = 1/38  = 0.026316,  profit = +\$350
lose: p = 37/38 = 0.973684,  profit = −\$10
EV = 0.026316(350) + 0.973684(−10) = 9.2105 − 9.7368 = −\$0.5263   (= −5.26% of \$10) ✓
```

**Red, \$100:**
```
EV = (18/38)(100) + (20/38)(−100) = 47.368 − 52.632 = −\$5.263      (= −5.26%) ✓
```

**Basket, \$10:**
```
EV = (5/38)(60) + (33/38)(−10) = 7.8947 − 8.6842 = −\$0.7895        (= −7.89%) ✗ worse
```

**A mixed table position** — \$5 red + \$5 on straight 17 + \$5 on the 1st dozen. Expectation
is **linear**, so no combination of bets can ever escape the edge:

```
E[total] = E[red] + E[17] + E[dozen] = −0.2632 − 0.2632 − 0.2632 = −\$0.7895
         = −5.263% × \$15  ✓
```

There is no hedge, no "covering the table", no combination whose EV is not `−5.263% × total staked`
(unless a basket is in the mix, which makes it worse). **This is the whole game, mathematically.**

### 3.4 Counting the available bet positions

For a complete implementation, the layout admits:

| Bet | Count |
|---|---|
| Straight | 38 |
| Split | 62 (24 horizontal in-row + 33 vertical + 0-00, 0-1, 0-2, 00-2, 00-3) |
| Street | 12 |
| Trio | 3 |
| Corner | 22 |
| Six line | 11 |
| Basket | 1 |
| Column | 3 |
| Dozen | 3 |
| Even-money | 6 |
| **Total distinct propositions** | **161** |

---

## 4. Probability

### 4.1 Formulas

For a bet covering set `S ⊆ {0, 00, 1…36}`, |S| = n, with payout a:1 and stake *s*:

```
P(win)      = n / 38
P(lose)     = (38 − n) / 38
Profit      = +a·s  (win)   |   −s  (lose)
E[Profit]   = s · (a·n − (38 − n)) / 38
House edge  = −E[Profit] / s
RTP         = 1 + E[Profit]/s = 1 − house edge
Var[Profit] = s² · ( E[X²] − E[X]² )
            = s² · ( (a²·n + (38−n)) / 38  −  ((a·n − 38 + n)/38)² )
```

### 4.2 House edge and RTP

```
House edge = 2/38 = 1/19 = 0.052631578…  ≈ 5.26%   (all bets except basket)
             3/38        = 0.078947368…  ≈ 7.89%   (basket only)

RTP        = 36/38 = 18/19 = 0.947368…   ≈ 94.74%
             35/38         = 0.921052…   ≈ 92.11%  (basket)
```

Comparison, for context:

| Variant | Pockets | House edge | RTP |
|---|---|---|---|
| American (0, 00) | 38 | **5.263%** | 94.74% |
| European (0) | 37 | 2.703% | 97.30% |
| French, *la partage* on even-money | 37 | 1.351% (even-money bets only) | 98.65% |
| American w/ Atlantic-City "surrender" | 38 | 2.632% (even-money bets only) | 97.37% |

The American wheel is **~1.95× worse** than the European one for identical play. The single
extra green pocket does all of that damage.

### 4.3 Why payouts are lower than true odds

The payout schedule is computed as if the wheel had **36 pockets**:

```
fair payout on a 36-pocket wheel:  (36 − n)/n     ← what the table actually pays
fair payout on a 38-pocket wheel:  (38 − n)/n     ← what would be break-even
```

So the casino pays "true odds minus the two zeros". Equivalently: **a winning bet is paid out
of a pool of 36 units while 38 units of possibility exist.** The 2/38 that is never returned
is the house's entire revenue. It is not a fee, a rake or a commission — it is *structural*,
embedded in the payout table, and it applies to every unit wagered, every spin, forever.

### 4.4 Variance

`Var = E[X²] − (E[X])²` per 1 unit staked. Since `E[X] = −1/19` for all non-basket bets,
`(E[X])² = 1/361 = 0.002770`, which is negligible; variance is dominated by `E[X²] = (a²n + 38 − n)/38`.

| Bet | E[X²] | Variance | **SD** |
|---|---|---|---|
| Straight (35:1) | (1225 + 37)/38 = 33.2105 | 33.2077 | **5.7626** |
| Split (17:1) | (578 + 36)/38 = 16.1579 | 16.1551 | **4.0193** |
| Street/Trio (11:1) | (363 + 35)/38 = 10.4737 | 10.4709 | **3.2358** |
| Corner (8:1) | (256 + 34)/38 = 7.6316 | 7.6288 | **2.7620** |
| Basket (6:1) | (180 + 33)/38 = 5.6053 | 5.5990 | **2.3662** |
| Six line (5:1) | (150 + 32)/38 = 4.7895 | 4.7867 | **2.1878** |
| Dozen/Column (2:1) | (48 + 26)/38 = 1.9474 | 1.9446 | **1.3945** |
| Even money (1:1) | (18 + 20)/38 = 1.0000 | 0.9972 | **0.9986** |

The straight-up bet has **5.77× the standard deviation** of red/black per unit staked. This is
the whole psychology of the game: the edge is identical (5.26%), but the variance is a factor
of 33 apart. Variance is what lets a player finish a session ahead; it never changes the mean.

**Over *N* independent spins, flat 1-unit bets** (i.i.d., so variance adds):

```
E[profit]  = −N/19            (grows linearly, ∝ N)
SD[profit] = σ·√N             (grows only as √N)

E/SD ratio = (N/19) / (σ√N) = √N / (19σ)
```

The ratio of drift to noise grows as `√N`. **This is the mathematical statement of "the house
always wins in the long run"** — and it is also why short sessions look winnable.

Break-even horizon (where expected loss = 1 SD, i.e. losing becomes the *typical* outcome):

```
N/19 = σ√N   →   N = (19σ)²
even money:  N = (19 × 0.9986)² ≈    360 spins
dozen:       N = (19 × 1.3945)² ≈    702 spins
straight:    N = (19 × 5.7626)² ≈ 11,988 spins
```

---

## 5. Wheel mechanics (physical)

### 5.1 The spin

```
   ┌──────────────────────── ball track (outer, stationary rim) ────────┐
   │   ●  ball, launched anti-clockwise at ~ 3–5 rev/s                  │
   │  ┌──────────────────────────────────────────────────────────┐      │
   │  │  ◄── rotor (wheel head), spun clockwise at ~ 0.5–1 rev/s  │      │
   │  │      38 pockets, separated by frets                       │      │
   │  │        ▲ ▲ ▲   8 deflectors ("diamonds"/"canoes") on the  │      │
   │  │                 apron between track and rotor             │      │
   │  └──────────────────────────────────────────────────────────┘      │
   └────────────────────────────────────────────────────────────────────┘
```

1. **Rotor spins** in one direction; the dealer launches the **ball in the opposite direction**
   along the outer track. House rules require a minimum number of ball revolutions (typically
   ≥ 3–4) before it may drop, and the dealer varies release point and speed.
2. **Ball decelerates** under rolling friction and air drag. It stays on the track while the
   centripetal requirement `v²/r` exceeds what the track's banking + gravity can supply.
3. **Drop-off.** Below a critical velocity `v_c` the ball leaves the track and falls inward.
4. **Deflectors.** Eight metal diamonds are placed asymmetrically (some vertical, some
   horizontal) precisely so that the ball's fall is *not* a smooth ballistic arc. A diamond
   strike converts the ball's tangential momentum into a chaotic bounce.
5. **Scatter.** After the first deflector hit, the ball bounces across pockets and frets. Empirical
   scatter is on the order of **±8 to ±15 pockets**, and the rotor keeps moving underneath it.
6. **Settle.** The ball comes to rest in a pocket. The dealer places the dolly on the winning
   number, clears losing bets, pays winners.

### 5.2 Why it is random

The dynamics are **deterministic but chaotic**: the system has positive Lyapunov exponents, so
uncertainty in initial conditions grows exponentially with time.

```
δ(t) ≈ δ₀ · e^{λt}
```

An uncertainty of `δ₀ ≈ 1 mm` in the ball's release position, amplified over ~10–20 seconds of
flight *and* passed through the deflector collisions (which are effectively a non-smooth map),
is enough to spread the outcome over all 38 pockets. Chaos does not merely make prediction
hard — combined with the deflector scatter it makes the outcome distribution converge to
(near-)uniform over the rotor's pockets.

**Caveat, honestly stated:** roulette is *not* provably random the way a CSPRNG is. The
Eudaemons (Doyne Farmer et al., late 1970s) and Thorp before them demonstrated that with a
concealed timing computer measuring rotor and ball velocity, one can predict the *half* of the
wheel the ball will land in, converting the −5.26% edge into roughly **+18%**. This works only
because a *tilted or worn* wheel, a *predictable* dealer, or a *slow* ball reduces the scatter.
It requires electronic aid (illegal in most jurisdictions) and is defeated by the "no more
bets" call being made *before* the ball drops.

### 5.3 Independence of spins

Spins are **i.i.d.**:

```
P(X_{n+1} = k  |  X_1, X_2, …, X_n)  =  P(X_{n+1} = k)  =  1/38     for every k, every history
```

The wheel has no state that encodes past outcomes. The rotor's angular position is continuous
and gets re-randomised by the dealer; the ball is re-launched from a hand-varied position at a
hand-varied speed. There is no physical channel through which "red came up 10 times" could
influence pocket 11 on the next spin. (See §9 for why intuition rebels at this.)

---

## 6. Casino algorithms

### 6.1 Physical roulette
Wheel + ball + dealer, as §5. Randomness source = chaotic mechanics. Integrity is maintained
by *procedure* (dealer rotation, minimum ball revolutions, ball swaps between different
diameters/materials, periodic rotor rebalancing and levelling) and by *statistical monitoring*
(§6.5).

### 6.2 Electronic / automated roulette
Still a **real wheel**; the ball is launched by a compressed-air or magnetic mechanism and the
winning pocket is read by an **optical/IR sensor ring** or camera. Players bet at terminals.
The randomness is still physical. Advantage to the house: 60–100 spins/hour instead of 30–40 —
and since the edge applies per unit wagered, **doubling the spin rate doubles the hourly loss
rate**, with no change to the edge.

### 6.3 RNG roulette (the model for our mini-game)

No physical wheel. The outcome is drawn from a CSPRNG. Two things must be correct:

**(a) The generator must be cryptographic.** `Math.random()` is *not*: V8's xorshift128+ state
is recoverable from a handful of outputs, making future spins predictable. Use
`crypto.getRandomValues` / `crypto.randomBytes` (ChaCha20 or platform CSPRNG), or an HMAC
construction (§6.4).

**(b) The mapping to 0…37 must be unbiased.** Naïve `rand_u32() % 38` is **biased**, because
2³² is not divisible by 38:

```
2³² = 4,294,967,296
4,294,967,296 mod 38 = 6
```

The six residues `0…5` therefore occur `⌈2³²/38⌉` times while the other 32 occur `⌊2³²/38⌋`
times — the first six pockets are over-represented by a factor of ~1 + 1.4×10⁻⁹. Tiny, but it
is a *systematic* bias, exactly the kind an auditor will fail you on. Fix with **rejection
sampling**:

```ts
// Uniform over [0, 38) with zero modulo bias.
function spinIndex(): number {
  const LIMIT = 4294967296 - (4294967296 % 38); // 4,294,967,290
  const buf = new Uint32Array(1);
  let r: number;
  do {
    crypto.getRandomValues(buf);
    r = buf[0];
  } while (r >= LIMIT);        // P(reject) = 6/2³² ≈ 1.4e-9
  return r % 38;               // exactly uniform
}
```

**Certification.** Real-money RNGs are audited against **GLI-19** (interactive gaming systems)
and **GLI-11** (gaming devices), or by eCOGRA / iTech Labs. The statistical batteries used:

| Battery | What it checks |
|---|---|
| Chi-square goodness of fit | pocket frequencies match 1/38 |
| Serial / lag correlation | `corr(X_i, X_{i+k}) ≈ 0` for all k |
| Runs test | streak lengths match a Bernoulli process |
| NIST SP 800-22 | 15 tests: frequency, block frequency, DFT, entropy, linear complexity, … |
| Dieharder / TestU01 **BigCrush** | 100+ tests; the strongest practical battery |

Chi-square for a roulette RNG over N spins, with `O_k` = observed count of pocket k:

```
χ² = Σ_{k=0}^{37} (O_k − N/38)² / (N/38)          df = 37
```

Reject uniformity at α = 0.01 if `χ² > 59.89`. Note: run this on *millions* of spins; at
N = 1,000 the test has almost no power.

### 6.4 Provably fair roulette

The player must be able to verify, *after the fact*, that the outcome was not chosen adversarially
once their bet was known. Standard **commit–reveal**:

```
Setup (before any bet):
  server: serverSeed ← 32 random bytes  (secret)
          commitment = SHA-256(serverSeed)          →  published to the player
  client: clientSeed ← chosen freely by the player  (this is the essential part —
                                                      it makes the outcome un-precomputable
                                                      by the server)
  nonce:  0, 1, 2, …  incremented per spin

Per spin:
  h        = HMAC-SHA256(key = serverSeed, msg = `${clientSeed}:${nonce}`)   → 32 bytes
  outcome  = uniform38(h)                                                    → 0…37

Reveal (on seed rotation / on demand):
  server publishes serverSeed
  player checks   SHA-256(serverSeed) == commitment      ← proves no swap
  player recomputes every h and every outcome            ← proves no manipulation
```

Uniform extraction from the hash, again with rejection to avoid modulo bias:

```ts
function uniform38(hash: Uint8Array): number {
  const LIMIT = 4294967296 - (4294967296 % 38);      // 4,294,967,290
  for (let i = 0; i + 4 <= hash.length; i += 4) {    // consume 4 bytes at a time
    const r =
      ((hash[i] << 24) >>> 0) + (hash[i + 1] << 16) + (hash[i + 2] << 8) + hash[i + 3];
    if (r < LIMIT) return r % 38;                    // accept
  }
  // 8 consecutive rejections has probability (6/2³²)⁸ ≈ 10⁻⁷⁰; re-hash for completeness.
  return uniform38(sha256(hash));
}
```

The three security properties:
- **Server cannot cheat**: `serverSeed` is committed before the bet, and changing it breaks
  `SHA-256(serverSeed) == commitment` (pre-image resistance).
- **Server cannot pre-compute a favourable outcome for a known bet**: the outcome depends on
  `clientSeed`, which the player chooses.
- **Client cannot predict**: without `serverSeed`, HMAC-SHA256 output is indistinguishable from
  random (PRF security).

The one property it does **not** give you: it does not make the game fair in the *EV* sense.
A provably-fair American roulette still has a 5.26% house edge. "Provably fair" means "the
outcome was honestly drawn", **not** "the odds are even".

### 6.5 Anti-cheat systems

| Attack | Mechanism | Countermeasure |
|---|---|---|
| **Past posting** | placing/raising a bet after the ball settles | "No more bets" called before drop; overhead cameras; RFID chips; dealer clears with hand sweep |
| **Wheel bias** | a worn/tilted wheel over-favours a sector; play the biased pockets | Continuous χ² monitoring of every wheel; rotor rotated relative to the bowl; frets replaced; wheels levelled and rebalanced |
| **Dealer signature** | a dealer with consistent release timing produces a predictable landing sector | Dealer rotation; mandated variation of release point/speed; minimum ball revolutions |
| **Roulette computers** | hidden device times rotor + ball, predicts the half-wheel (Thorp/Eudaemons) | Legally banned; "no more bets" *before* the drop; low-profile scatter-maximising deflectors |
| **Magnetic/gaffed ball** | ball with ferrous core steered by a coil in the bowl | Ball weighed/swapped regularly; balls of varying size and material; wheel inspection |
| **Chip fraud** | counterfeit or colour-swapped chips | Unique per-table colour chips (no cash value away from the table); UV/RFID markers |
| **Collusion with dealer** | dealer pays a losing bet / mis-places the dolly | Pit boss + surveillance; automatic bet-and-payout reconciliation |
| **RNG attack (online)** | predicting `Math.random()`, replaying seeds, exploiting modulo bias | CSPRNG; rejection sampling; server-authoritative outcomes; commit–reveal; rate limiting; per-account anomaly detection on win-rate z-scores |

For an **online** implementation, the two non-negotiables:
1. **The server decides the outcome.** Never let the client generate the spin. A client-side
   RNG is trivially patched in devtools.
2. **The server validates the bet against a whitelist** (§10.3) *before* the spin, and computes
   the payout itself. Never trust a client-submitted "I won X".

---

## 7. Betting strategies

### 7.0 The theorem that kills all of them

Let `B_i` be the stake on spin *i*. A **betting system** is any rule where `B_i` is a function
of the outcomes `X_1, …, X_{i−1}` (i.e. the stake sequence is *predictable* w.r.t. the natural
filtration). Let `R_i` be the per-unit return on spin *i*, so `E[R_i | ℱ_{i−1}] = −1/19`, and
`R_i` is independent of `ℱ_{i−1}`. Total profit after a stopping time *T*:

```
E[Profit] = E[ Σ_{i=1}^{T} B_i · R_i ]
          = E[ Σ_{i=1}^{T} B_i · E[R_i | ℱ_{i−1}] ]      (tower property; B_i is ℱ_{i−1}-measurable)
          = −(1/19) · E[ Σ_{i=1}^{T} B_i ]
          = −(1/19) · E[total amount wagered]
```

(valid for any stopping time *T* with `E[T] < ∞` and bounded bets — optional stopping theorem;
the discounted bankroll is a **supermartingale**.)

> **Every betting system's expected loss equals 5.263% of the total amount it wagers.**
> Nothing else is possible. A system can only change *how much you wager* and *the shape of the
> profit distribution* — never the mean. Systems that "win often" do so by risking a lot to win
> a little; systems that "win big" do so rarely. The product is invariant.

Below, `q = 20/38 = 0.526316` = P(lose an even-money bet), `p = 18/38 = 0.473684`.

### 7.1 Martingale

**Logic.** Double after every loss; the first win recovers all losses plus one base unit.
After *k* losses you have staked `1 + 2 + … + 2^{k−1} = 2^k − 1`; the next bet of `2^k` pays
`2^k`, netting `+1`.

**Advantages.** Wins a unit with very high probability per session. Feels infallible.

**Weaknesses.** Requires *unbounded* bankroll **and** *unbounded table maximum*. Both are finite.
Stake grows as `2^k` — from a \$5 base, level 10 is \$5,120, past most table maxima.

**Long-term expectation.** Suppose you can survive *k* consecutive losses (bankroll `2^k − 1`).
Session outcome: `+1` with probability `1 − q^k`, `−(2^k − 1)` with probability `q^k`.

```
E[session] = (1 − q^k)·1 − q^k·(2^k − 1) = 1 − q^k·2^k = 1 − (2q)^k
```

Since `2q = 40/38 = 1.052632 > 1`, `(2q)^k > 1` for all k ≥ 1 — **the EV is negative for every k**,
and it gets *worse* the deeper you allow the progression. Worked, `k = 10` (bankroll 1,023 units):

```
q^10       = 0.526316^10                = 0.0016307      ← P(ruin per session)
E[session] = 1 − (1.052632)^10 = 1 − 1.670128 = −0.670128 units

cross-check via the theorem:
E[total wagered per session] = Σ_{i=0}^{9} 2^i q^i = Σ (2q)^i = ((2q)^10 − 1)/(2q − 1)
                             = (1.670128 − 1)/0.052632 = 12.7324 units
E[session] = −(1/19) × 12.7324 = −0.670128  ✓ identical
```

Expected number of sessions until ruin: `1/0.0016307 ≈ 613`. You collect ≈ +613 units, then lose
1,023 in one afternoon. Net ≈ −410 units. **The Martingale converts a small, frequent win into a
rare, catastrophic loss — a short volatility position with negative carry.**

```
Martingale P&L shape (k=10):
  99.84% of sessions:  +1
   0.16% of sessions:  −1023
  ────────────────────────────
  mean:              −0.670
```

### 7.2 Reverse Martingale (Paroli)

**Logic.** Double after every **win**, reset after a loss (or after *m* consecutive wins).

**Long-term expectation.** With a 3-win target: win `2^3 − 1 = 7`… no — you win `2^m − 1` only if
you stop; concretely, staking 1 → 2 → 4 and banking after 3 wins yields `+7` with probability
`p³ = 0.106265`, and `−1` otherwise. Wait, that's not right either: a loss at step 2 loses the
1 unit you won, etc. Netting it out:

```
lose spin 1:              −1        p = q                = 0.526316
win 1, lose 2:            −1        p = p·q              = 0.249307
win 1,2, lose 3:          −1        p = p²·q             = 0.118093
win 1,2,3 (bank):         +7        p = p³               = 0.106284
E = 7(0.106284) − 1(0.893716) = 0.743988 − 0.893716 = −0.149728 units
E[wagered] = 1 + p(2) + p²(4) = 1 + 0.947368 + 0.897507 = 2.844875
−(1/19)(2.844875) = −0.149730  ✓
```

**Advantages.** Losses are capped at the base unit; it presses a winning streak with the
house's money. Strictly lower risk of ruin than Martingale.
**Weaknesses.** Long losing grind punctuated by rare big wins; the streak almost always breaks.
**Expectation.** −5.263% of turnover. Unchanged.

### 7.3 Fibonacci

**Logic.** Stake follows `1, 1, 2, 3, 5, 8, 13, 21, 34, …`; advance one term on a loss, retreat
**two** terms on a win. Because `F_n = F_{n−1} + F_{n−2}`, a win at term *n* recovers the losses
of terms *n−1* and *n−2*.

**Advantages.** Grows slower than the Martingale (φ ≈ 1.618 vs 2), so it survives longer losing
runs on the same bankroll.
**Weaknesses.** Recovery is *partial* — a single win does not clear the whole drawdown; you can
be far behind while deep in the sequence. And it *still* grows exponentially: term 15 is 610.
**Expectation.** −5.263% of turnover. The slower growth just means it takes longer to reach
the table maximum, i.e. it *delays* the same fate.

### 7.4 D'Alembert

**Logic.** Stake +1 unit after a loss, −1 unit after a win. Rests on the (false) premise that
wins and losses "even out".

**Advantages.** Very gentle progression; low variance; hard to hit the table max.
**Weaknesses.** The premise is the gambler's fallacy in its purest form. If wins and losses were
equally likely, the system would break even; they are **not** — you win 47.37% and lose 52.63%,
so the stake ratchets *upward* on average, meaning you have your **largest stakes on your
worst runs**. In a fair game (p = 0.5), completing a cycle of *n* wins and *n* losses nets 0;
in roulette, the excess losses guarantee a positive drift in bet size.
**Expectation.** −5.263% of turnover.

### 7.5 Labouchere (cancellation)

**Logic.** Write a list, e.g. `1 2 3 4`. Bet `first + last` (= 5). On a win, cross off both
ends. On a loss, append the amount lost to the end. The list empties exactly when you have won
the sum of the original list (here 1+2+3+4 = **10**).

```
list 1 2 3 4   bet 5   LOSS → 1 2 3 4 5
list 1 2 3 4 5 bet 6   WIN  →   2 3 4
list 2 3 4     bet 6   WIN  →     3
list 3         bet 3   WIN  →   (empty) → +10 banked
```

**Advantages.** Flexible target; wins a *pre-chosen* amount; survives many losses.
**Weaknesses.** The list **grows on losses**, so bets grow without bound; a bad run leaves an
enormous list and enormous required bets. It is a Martingale with extra steps.
**Expectation.** −5.263% of turnover. P(completing the list) is high; the rare failure is
proportionally devastating — same distribution shape as §7.1.

### 7.6 Oscar's Grind

**Logic.** Target exactly **+1 unit per cycle**. Bet 1 unit. After a **loss**, keep the stake
unchanged. After a **win**, increase the stake by 1 unit — but never bet more than what is
needed to reach +1 for the cycle. Reset on reaching +1.

**Advantages.** The slowest-growing of all the progressions; escalates only when *winning*
(i.e. with the house's money); very high probability of eventually banking +1.
**Weaknesses.** Cycles can run for hundreds of spins in a chop; the drawdown while grinding
back is unbounded and stakes do climb. Requires a large bankroll relative to the +1 target.
**Expectation.** −5.263% of turnover.

### 7.7 Flat betting

**Logic.** Same stake every spin. Not a system — the *absence* of one.

**Advantages.** Minimises turnover for a given number of spins, hence **minimises expected
loss for a given amount of play**. Lowest variance. Bankroll survives longest. Wholly transparent.
**Weaknesses.** Cannot manufacture a winning session out of a losing game.
**Expectation.** −5.263% of turnover — but turnover is `N × stake`, the *smallest* it can be
for *N* spins. **Flat betting is therefore the mathematically optimal way to play, if you
insist on playing.**

### 7.8 Summary

| System | Stake growth | P(session win) | Loss when it fails | E[profit] | Beats the edge? |
|---|---|---|---|---|---|
| Martingale | 2ⁿ (exponential) | very high (≈99.8%) | catastrophic (−1023) | −5.263% of turnover | **No** |
| Reverse Martingale | 2ⁿ on wins | low | small (−1) | −5.263% of turnover | **No** |
| Fibonacci | φⁿ ≈ 1.618ⁿ | high | severe | −5.263% of turnover | **No** |
| D'Alembert | linear | moderate | moderate | −5.263% of turnover | **No** |
| Labouchere | superlinear | high | severe | −5.263% of turnover | **No** |
| Oscar's Grind | slow, on wins only | high | moderate–severe | −5.263% of turnover | **No** |
| Flat | constant | ≈ 47% per spin | bounded | −5.263% of turnover | **No** (but minimal) |

**Why none of them can work.** Three independent proofs:

1. **Linearity of expectation.** `E[ΣB_iR_i] = ΣE[B_i]·(−1/19)`. A sum of negative numbers is
   negative. Choosing *when* and *how much* to bet cannot change the sign of a constant.
2. **Optional stopping / supermartingale.** Bankroll `M_n` satisfies `E[M_{n+1}|ℱ_n] ≤ M_n`.
   No stopping rule *T* (including "quit when ahead") makes `E[M_T] > M_0`.
3. **Independence.** Every system implicitly claims past outcomes carry information about the
   next spin. They carry none: `P(X_{n+1}=k | history) = 1/38` always. A function of pure
   noise is still pure noise.

The *only* thing a system controls is the **shape of the P&L distribution** — trading a high
probability of a small win against a small probability of a large loss (Martingale et al.),
or the reverse (Paroli). The **mean is nailed to −5.263% of turnover** by the payout table.

---

## 8. Simulation

Flat **\$10** on **red** (even money), and separately flat **\$10 straight-up** on 17.
`E[profit] = −0.0526316 × 10 × N`. `SD = 10 × σ × √N`.

### 8.1 Expected results

| N | Total wagered | **E[profit]** | SD (red) | 1σ band (red) | P(ahead) red | SD (straight) | P(ahead) straight |
|---|---|---|---|---|---|---|---|
| **100** | \$1,000 | **−\$52.63** | \$99.86 | −\$152 … +\$47 | **26.5%** | \$576.26 | **48.9%** |
| **1,000** | \$10,000 | **−\$526.32** | \$315.79 | −\$842 … −\$211 | **4.5%** | \$1,822.36 | **40.7%** |
| **100,000** | \$1,000,000 | **−\$52,631.58** | \$3,157.87 | −\$55,789 … −\$49,474 | **≈ 10⁻⁶⁰** | \$18,223.60 | **0.2%** |

Derivations for the red column (profit `= 20W − 10N` where `W ~ Binomial(N, 18/38)`; ahead ⟺ `W > N/2`):

```
N = 100:      W ~ Bin(100, 0.473684),  μ = 47.368,  σ = 4.993
              P(W ≥ 51) ≈ 1 − Φ((50.5 − 47.368)/4.993) = 1 − Φ(0.627) = 0.265

N = 1,000:    μ = 473.68,  σ = 15.79
              P(W ≥ 501) ≈ 1 − Φ((500.5 − 473.68)/15.79) = 1 − Φ(1.698) = 0.045

N = 100,000:  μ = 47,368,  σ = 157.9
              P(W ≥ 50,001) ≈ 1 − Φ((50,000.5 − 47,368.4)/157.9) = 1 − Φ(16.67) ≈ 1.3 × 10⁻⁶²
```

### 8.2 Reading the table

```
        expected loss  ∝ N        (linear)      ──────────────────►  dominates
        noise (SD)     ∝ √N       (sublinear)   ─────►

  N=100        loss $53   vs   noise $100    →   noise wins: a winning session is common
  N=1,000      loss $526  vs   noise $316    →   loss wins: ahead only 1 time in 22
  N=100,000    loss $52k  vs   noise $3.2k   →   loss wins by 16σ: mathematically certain
```

**A single evening (~100 spins) is a coin-flip-ish experience. A year of weekly play is not.**

### 8.3 Straight-up: why variance is the marketing department

At N = 100, betting \$10 on a single number leaves you **48.9% likely to be ahead** — barely
worse than a coin flip — despite an identical 5.26% edge. The distribution is wildly skewed:
you most likely hit 2 or 3 times (each +\$350) and the modal outcome is *near* break-even.
By N = 100,000 that probability has collapsed to 0.2%. **Variance does not hide the edge; it
only postpones the reckoning.**

### 8.4 Martingale simulation (base \$5, bankroll \$5,115 = 10 levels)

```
per session:  +$5     with probability 0.998369
              −$5,115 with probability 0.001631
E[session]  = −$3.35
E[wagered]  = 12.7324 × $5 = $63.66   →   −5.263% × $63.66 = −$3.35  ✓

1,000 sessions:  E ≈ −$3,351;  P(at least one ruin) = 1 − 0.998369^1000 = 80.4%
```

You will win \$5 about 998 times and lose \$5,115 about 1.6 times. It is not a strategy; it is
a way to *reshape* the same expected loss.

### 8.5 Reference simulator

```ts
function simulate(spins: number, stake: number, covers: number, payout: number) {
  let profit = 0;
  for (let i = 0; i < spins; i++) {
    profit += spinIndex() < covers ? stake * payout : -stake;  // covers = |S|
  }
  return profit;
}
// simulate(100_000, 10, 18, 1)   →  ≈ −52,600  (±3,200 at 1σ)
// simulate(100_000, 10,  1, 35)  →  ≈ −52,600  (±18,200 at 1σ)   ← same mean, 5.8× the noise
```

---

## 9. Common misconceptions

All of these are corollaries of one fact: **spins are i.i.d.** `P(X_{n+1}=k | anything) = 1/38`.

### 9.1 "Hot numbers"
*Claim:* 17 has hit 5 times in 100 spins, it's running hot, bet it.
*Reality:* In 100 spins, `E[hits on 17] = 100/38 = 2.63`, `SD = √(100 · (1/38) · (37/38)) = 1.60`.
Five hits is `(5 − 2.63)/1.60 = 1.48σ` — utterly unremarkable. And with 38 numbers, the *maximum*
count across all of them is expected to be high **by construction**: you are looking at the
max of 38 correlated Poisson-ish variables and calling the winner "hot". This is the **multiple
comparisons fallacy** (a.k.a. the Texas sharpshooter). The next spin still pays 35:1 on a 1/38
event → EV = −5.26%.

### 9.2 "Cold numbers"
*Claim:* 8 hasn't hit in 200 spins, it's overdue / it's a dead number.
*Reality:* `P(no 8 in 200 spins) = (37/38)^200 = 0.0048`. Rare *for a specified number chosen in
advance* — but you didn't specify it in advance, you looked for the longest gap. Expected number
of pockets that go 200 spins unhit `= 38 × 0.0048 = 0.18` — so seeing one happens routinely
across a few wheels and a few sessions. Either way, the next spin: **1/38, EV −5.26%**.

### 9.3 "Due numbers" (the Gambler's Fallacy)
*Claim:* Red has come up 10 times in a row, black is due.
*Reality:* `P(next is black) = 18/38 = 47.37%` — **exactly what it was before the streak**.
`P(10 reds in a row) = (18/38)^10 = 0.000546`, which is small *ex ante*, but you are not being
asked about 10 reds — you are being asked about spin 11, **conditioned on** the first 10, and
conditioning on independent events changes nothing:

```
P(R₁₁ | R₁…R₁₀) = P(R₁₁) = 18/38
```

The **Law of Large Numbers does not work by compensation.** It works by *dilution*: the wheel
never "makes up" a deficit; the deficit simply becomes negligible relative to a growing N.

```
after 10 spins:   10 reds, 0 blacks   → red fraction 100%,  absolute gap 10
after 10,000:     ~4,750 reds…        → red fraction ≈ 50%, absolute gap grows ∝ √N

E[|#red − #black|] ≈ σ√N  → grows without bound.  The RATIO converges; the GAP diverges.
```

### 9.4 "Pattern prediction"
*Claim:* R-R-B-R-R-B… there's a pattern; ride it.
*Reality:* Any specific sequence of *n* outcomes has probability `(1/38)^n`. Patterns appear in
random data with exactly the frequency randomness predicts — humans are pattern-detectors with
an enormous false-positive rate (apophenia). A truly random binary-ish stream that *lacked*
long streaks would itself be non-random. Formally, the outcome sequence has maximal entropy:
`H(X) = log₂38 = 5.248 bits/spin`, and `I(X_{n+1}; X_1…X_n) = 0` — **the mutual information
between the history and the next spin is exactly zero bits**. There is nothing to predict from.

### 9.5 "Wheel memory"
*Claim:* The wheel/RNG "knows" it owes a payout.
*Reality:* A physical wheel is a mass of metal with no state variable encoding history. An RNG's
state is either re-seeded from a CSPRNG or advanced by a PRF; in neither case does past output
feed back into the outcome distribution. (An RNG that *did* compensate — "we're down, force a
loss" — would be **fraud**, would be detectable by χ² and serial-correlation tests, and is what
certification under GLI-19 exists to rule out.)

### 9.6 The one that *isn't* a misconception
**Wheel bias is real** — on *physical* wheels. A worn fret, a tilted rotor or a rough pocket can
genuinely make some pockets more likely (Joseph Jagger, Monte Carlo, 1873; Gonzalo García-Pelayo,
1990s). Detecting it requires ~5,000–10,000+ logged spins **on one specific wheel** and a χ² test
with df = 37 (reject uniformity at α=0.01 when χ² > 59.89), and modern casinos monitor for it
continuously. **It does not exist at all on an RNG wheel** — which is exactly the point of §6.3.

---

## 10. Building the game

### 10.1 Data structures

```ts
// ---- The wheel ------------------------------------------------------------
export type Pocket = '0' | '00' | `${number}`;           // '0' | '00' | '1'…'36'
export type Colour = 'red' | 'black' | 'green';

/** Physical clockwise order. Index = position on the rotor; also the RNG's output space. */
export const WHEEL: readonly Pocket[] = [
  '0','28','9','26','30','11','7','20','32','17','5','22','34','15','3','24','36','13','1',
  '00','27','10','25','29','12','8','19','31','18','6','21','33','16','4','23','35','14','2',
] as const;                                              // length 38 — assert this in a test

const RED = new Set(['1','3','5','7','9','12','14','16','18','19','21','23','25','27','30','32','34','36']);

export function colourOf(p: Pocket): Colour {
  if (p === '0' || p === '00') return 'green';
  return RED.has(p) ? 'red' : 'black';
}

// ---- Bets -----------------------------------------------------------------
export type BetType =
  | 'straight' | 'split' | 'street' | 'trio' | 'corner' | 'basket' | 'sixline'
  | 'column'   | 'dozen' | 'red' | 'black' | 'odd' | 'even' | 'low' | 'high';

/** Payout (x:1) is a pure function of how many pockets the bet covers — except basket. */
export const PAYOUT: Record<BetType, number> = {
  straight: 35, split: 17, street: 11, trio: 11, corner: 8,
  basket: 6,                     // ← 6, NOT 6.6. This is the 7.89% bet.
  sixline: 5, column: 2, dozen: 2,
  red: 1, black: 1, odd: 1, even: 1, low: 1, high: 1,
};

export interface Bet {
  type: BetType;
  /** The chip's position, normalised: the covered pockets. Empty for the fixed outside bets. */
  selection: Pocket[];
  amount: number;
}
```

### 10.2 Spin generation

Server-authoritative, provably fair, unbiased (§6.3–6.4):

```ts
import { createHmac, createHash, randomBytes } from 'node:crypto';

export interface Round { serverSeed: string; commitment: string; clientSeed: string; nonce: number; }

export function commit(): { serverSeed: string; commitment: string } {
  const serverSeed = randomBytes(32).toString('hex');
  return { serverSeed, commitment: createHash('sha256').update(serverSeed).digest('hex') };
}

export function spin(r: Round): Pocket {
  const h = createHmac('sha256', r.serverSeed).update(`${r.clientSeed}:${r.nonce}`).digest();
  return WHEEL[uniform38(h)];
}

/** Uniform on [0,38) by rejection sampling — no modulo bias. */
function uniform38(h: Buffer): number {
  const LIMIT = 4_294_967_296 - (4_294_967_296 % 38);   // 4_294_967_290
  for (let i = 0; i + 4 <= h.length; i += 4) {
    const r = h.readUInt32BE(i);
    if (r < LIMIT) return r % 38;
  }
  return uniform38(createHash('sha256').update(h).digest());  // P ≈ 10⁻⁷⁰
}
```

### 10.3 Bet evaluation

The critical rule: **derive the covered set from the bet type, never trust a client-supplied set.**

```ts
/** The set of pockets a bet covers. Throws on an illegal chip position. */
export function covers(bet: Bet): Set<Pocket> {
  switch (bet.type) {
    case 'red':   return new Set(ALL.filter(p => colourOf(p) === 'red'));
    case 'black': return new Set(ALL.filter(p => colourOf(p) === 'black'));
    case 'odd':   return new Set(NUM.filter(n => n % 2 === 1).map(String) as Pocket[]);
    case 'even':  return new Set(NUM.filter(n => n % 2 === 0).map(String) as Pocket[]);
    case 'low':   return new Set(NUM.filter(n => n >= 1  && n <= 18).map(String) as Pocket[]);
    case 'high':  return new Set(NUM.filter(n => n >= 19 && n <= 36).map(String) as Pocket[]);
    case 'basket': return new Set<Pocket>(['0', '00', '1', '2', '3']);
    default: {
      // straight/split/street/trio/corner/sixline/column/dozen: validate the selection
      // against the precomputed legal-position table (161 propositions, §3.4).
      const key = bet.selection.slice().sort().join(',');
      const legal = LEGAL[bet.type].get(key);
      if (!legal) throw new Error(`illegal ${bet.type} position: ${key}`);
      return legal;
    }
  }
}

export interface Settlement { bet: Bet; won: boolean; payout: number; profit: number; }

export function settle(bets: Bet[], result: Pocket): Settlement[] {
  return bets.map(bet => {
    const won = covers(bet).has(result);
    const payout = won ? bet.amount * (PAYOUT[bet.type] + 1) : 0;  // stake + winnings
    return { bet, won, payout, profit: payout - bet.amount };      // profit = ±
  });
}
```

`LEGAL` is generated once from the layout geometry (rows of 3, 12 rows) — **not hand-typed**.
This is what makes an illegal 5-number "split" impossible, and it is where a lazy implementation
gets robbed.

### 10.4 Payout calculation

```
payout(bet)  = won ? amount × (odds + 1) : 0      ← returned to the player (stake included)
profit(bet)  = payout − amount                    ← ± change to bankroll
round profit = Σ profit(bet)  over all bets
```

Use **integer minor units** (cents), never floats — `0.1 + 0.2 !== 0.3`, and a rounding error in
a payout loop is a solvency bug. Reject any bet where `amount < tableMin` or `amount > maxFor(type)`
(§2.1), and validate the *aggregate* inside minimum separately from the *per-bet* outside minimum.

### 10.5 RNG validation

Ship these as tests, not as hopes:

```ts
it('is uniform over 38 pockets', () => {
  const N = 3_800_000, counts = new Array(38).fill(0);
  for (let i = 0; i < N; i++) counts[spinIndex()]++;
  const expected = N / 38;
  const chi2 = counts.reduce((s, o) => s + (o - expected) ** 2 / expected, 0);
  expect(chi2).toBeLessThan(69.35);          // df = 37, α = 0.001 critical value
});

it('has no serial correlation', () => { /* corr(Xᵢ, Xᵢ₊₁ … Xᵢ₊₁₀) ≈ 0 */ });

it('pays exactly 36/38 back', () => {
  // Monte-Carlo the RTP of every bet type; assert 0.9474 ± 3σ (0.9211 for basket).
});

it('has 38 pockets, 18 red, 18 black, 2 green, alternating colours', () => { /* … */ });
```

Also assert the **invariant that catches every payout bug**:
`for every non-basket bet: PAYOUT[type] === (36 - n) / n` where `n = covers(bet).size`.

### 10.6 Fairness verification (player-facing)

Expose a verifier so a player can check any past round:

```
GET  /rounds/:id        → { commitment, clientSeed, nonce, result }        (before reveal)
POST /seeds/rotate      → reveals the old serverSeed, commits a new one
GET  /verify            → the algorithm, in the open

Player-side check:
  1. sha256(serverSeed) === commitment                     ← the seed wasn't swapped
  2. HMAC_SHA256(serverSeed, `${clientSeed}:${nonce}`)
     → uniform38 → WHEEL[i] === result                     ← the outcome wasn't manipulated
```

Publish the code. The whole security argument is that the algorithm's **secrecy is irrelevant**;
only `serverSeed` is secret, and only until the reveal.

---

---

## 11. How this is actually built in FinSight

The design above is what got implemented, with one deliberate divergence: **the spin is drawn on
the server, not in the browser.** LuckyMe plays with the user's real wallet balance, so a
client-side RNG would be one devtools breakpoint away from picking its own winner.

| Concern | Where it lives |
|---|---|
| Wheel, 161 legal positions, payouts, `SecureRandom` spin | `services/transaction-service/…/game/Roulette.java` |
| Round: classify → spin → settle → write money → ban | `…/game/GameService.java` |
| `POST /api/v1/game/roulette/spin`, `GET …/status` | `…/game/GameController.java` |
| Debt lockout ladder | `…/game/BanTier.java`, `game_bans` (V7) |
| Wheel drawing + payout/odds preview only | `web/src/games/roulette/engine.ts` |
| Table UI, animation to the server's pocket | `web/src/games/roulette/Roulette.tsx` |
| Invariant tests (`payout == (36−n)/n`, colour alternation, uniformity) | `RouletteTest.java` |

**The money is real (as real as anything in this app).** A round settles to exactly **one net
transaction** against the wallet — a loss is an `EXPENSE` in category *Games*, a win an `INCOME`
in *Winnings* — through the normal transaction path. So a losing streak shows up in the
dashboard, eats into budgets, and trips the risk rules (5 spins inside 10 minutes *is*
`RAPID_SPENDING`, and it is correct that it fires).

**Debt gets you locked out.** Play needs a positive balance; a single round may take the wallet
negative (the stake is capped at balance + a bounded overdraft), and a negative balance bans the
account from the games. The ladder escalates on two axes:

```
debt ≤ 1M  → 5 minutes      and each prior ban bumps the tier one step further:
debt ≤ 5M  → 1 hour         tier = min(tierFromDebt + priorBans, ONE_WEEK)
debt ≤ 20M → 6 hours
debt ≤ 50M → 1 day
debt > 50M → 1 week
```

The ban row lives in MySQL, so clearing localStorage buys nothing — the next `spin` call is
refused with `403 GAME_BANNED` regardless of what the client believes.

**What is not built:** provably-fair commit–reveal (§6.4). It is the right design for a game with
anything at stake; here the numbers are invented and the server has no incentive to cheat its own
user, so the honest answer is that it would be ceremony. The rejection-sampling and CSPRNG parts
*are* built, because those are correctness, not ceremony.

---

## Appendix — constants

```
pockets                 38
house edge (all bets)   2/38 = 1/19 = 5.263157894736842…%
house edge (basket)     3/38        = 7.894736842105263…%
RTP                     36/38       = 94.736842105263158…%
fair payout             (38 − n)/n
actual payout           (36 − n)/n
payout shortfall        2/n         → EV = −(2/n)(n/38) = −2/38, independent of n
entropy per spin        log₂ 38     = 5.2479 bits
E[profit], N spins      −N × 0.0526316 × stake
SD[profit], N spins     σ × stake × √N
χ² critical (df=37)     51.00 (α=.05)   59.89 (α=.01)   69.35 (α=.001)
```
