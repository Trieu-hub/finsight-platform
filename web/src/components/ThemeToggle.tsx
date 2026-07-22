import { useI18n } from '../i18n'
import { useTheme } from '../theme'

/**
 * Light/dark switch for the header.
 *
 * <p>Treated as a scene change rather than a state flip. Nothing moves all at once: going to
 * light, the stars leave first, the knob sets off, the crescent slides open, and only then does
 * the sun bloom — going back to dark the order reverses, so each direction has its own read.
 * That staggering, not the duration, is what makes it feel deliberate.
 *
 * <p>Easing is the second half of it. The knob uses a curve that dips slightly backwards before
 * it sets off and drifts a little past its mark on arrival, the way a real object with weight
 * would. Everything else rides a slow-in/slow-out quart curve.
 *
 * <p>Only transform and opacity animate, so this stays on the compositor. Under
 * prefers-reduced-motion the whole choreography collapses to an instant change.
 */

/** Slow in, slow out, long tail — the "scene transition" feel. */
const EASE_SCENE = 'cubic-bezier(0.76, 0, 0.24, 1)'
/** Anticipates backwards, then overshoots — gives the knob apparent weight. */
const EASE_WEIGHTED = 'cubic-bezier(0.62, -0.28, 0.27, 1.22)'

/**
 * The choreography, in ms. Each entry is [delay going to LIGHT, delay going to DARK], so the two
 * directions can be ordered differently instead of one being the other played backwards.
 */
const T = {
  track: { d: 1000, delay: [0, 0] },
  stars: { d: 620, delay: [0, 420] }, // + per-star stagger below
  cloud: { d: 900, delay: [260, 0] },
  knob: { d: 880, delay: [140, 140] },
  glow: { d: 900, delay: [140, 140] },
  crescent: { d: 720, delay: [300, 240] },
  rays: { d: 660, delay: [520, 0] },
} as const

export default function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const { t } = useI18n()
  const isDark = theme === 'dark'
  const dir = isDark ? 1 : 0 // index into the delay pairs above

  /** Timing for one element, as an inline style so the whole schedule stays readable above. */
  const timing = (k: keyof typeof T, extraDelay = 0, ease = EASE_SCENE) => ({
    transitionDuration: `${T[k].d}ms`,
    transitionDelay: `${T[k].delay[dir] + extraDelay}ms`,
    transitionTimingFunction: ease,
  })

  return (
    <button
      type="button"
      onClick={toggle}
      role="switch"
      aria-checked={isDark}
      aria-label={t('theme.label')}
      title={t(isDark ? 'theme.toLight' : 'theme.toDark')}
      data-theme-toggle
      style={timing('track')}
      className={`group relative h-8 w-[62px] shrink-0 overflow-hidden rounded-full border transition-colors motion-reduce:transition-none ${
        isDark
          ? // Flat, not a gradient: the crescent below is cut by painting a disc in the track's
            // own colour, and it can only disappear against a single flat value.
            'border-neutral-700 bg-neutral-900'
          : 'border-sky-300 bg-gradient-to-b from-sky-300 to-sky-200'
      } focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-400 focus-visible:ring-offset-2 focus-visible:ring-offset-neutral-950`}
    >
      {/* Night sky. Each star carries its own extra delay so they never leave in lockstep. */}
      <span aria-hidden className="pointer-events-none absolute inset-0">
        {STARS.map((s, i) => (
          <span
            key={i}
            style={{ left: s.left, top: s.top, width: s.size, height: s.size, ...timing('stars', s.stagger) }}
            className={`absolute rounded-full bg-white transition-all motion-reduce:transition-none ${
              isDark ? 'translate-y-0 scale-100 opacity-90' : '-translate-y-1.5 scale-50 opacity-0'
            }`}
          />
        ))}
      </span>

      {/* Daylight cloud, drifting in from the right well after the sky has started to turn. */}
      <span
        aria-hidden
        style={timing('cloud')}
        className={`pointer-events-none absolute right-2 top-[9px] transition-all motion-reduce:transition-none ${
          isDark ? 'translate-x-4 opacity-0' : 'translate-x-0 opacity-80'
        }`}
      >
        <span className="block h-2 w-4 rounded-full bg-white" />
        <span className="absolute -top-1 left-1 block h-2.5 w-2.5 rounded-full bg-white" />
      </span>

      {/* Glow trailing the knob — wider and warm in light, where it reads as sunlight. */}
      <span
        aria-hidden
        style={timing('glow')}
        className={`pointer-events-none absolute top-1/2 h-8 w-8 -translate-y-1/2 rounded-full blur-md transition-all motion-reduce:transition-none ${
          isDark ? 'left-0 bg-sky-300/20' : 'left-[28px] bg-amber-300/70'
        }`}
      />

      {/* The knob. Rotating it a third of a turn as it travels keeps the move from reading flat. */}
      <span
        aria-hidden
        style={timing('knob', 0, EASE_WEIGHTED)}
        className={`absolute top-1/2 flex h-6 w-6 items-center justify-center rounded-full shadow-lg transition-all motion-reduce:transition-none ${
          isDark
            ? 'left-1 -translate-y-1/2 rotate-0 bg-gradient-to-br from-neutral-200 to-neutral-400'
            : // 60px inner width − 24px knob − 4px gap = 32px, mirroring left-1 on the other side.
              'left-[32px] -translate-y-1/2 rotate-[120deg] bg-gradient-to-br from-amber-200 to-amber-400'
        }`}
      >
        {/*
          The morph. This disc is the track's own colour: parked over the knob it bites a crescent
          out of it (the moon); slid off and shrunk, the knob is a whole disc again (the sun).
        */}
        <span
          style={timing('crescent')}
          className={`absolute h-5 w-5 rounded-full bg-neutral-900 transition-all motion-reduce:transition-none ${
            isDark ? 'translate-x-1.5 -translate-y-1 scale-100 opacity-100' : 'translate-x-4 -translate-y-4 scale-0 opacity-0'
          }`}
        />

        {/* Sun rays: bloom out of the centre only once the crescent has finished opening. */}
        <span
          style={timing('rays')}
          className={`absolute inset-0 transition-all motion-reduce:transition-none ${
            isDark ? 'scale-50 opacity-0' : 'scale-100 opacity-100'
          }`}
        >
          {RAYS.map((deg) => (
            <span
              key={deg}
              className="absolute left-1/2 top-1/2 h-[13px] w-[2px] rounded-full bg-amber-400"
              // Centring is folded into the transform: an inline transform would otherwise
              // override the -translate-*-1/2 utilities and knock every ray off-centre.
              style={{ transform: `translate(-50%, -50%) rotate(${deg}deg) translateY(-11px)` }}
            />
          ))}
        </span>
      </span>
    </button>
  )
}

const RAYS = [0, 45, 90, 135, 180, 225, 270, 315]

const STARS = [
  { left: '11px', top: '7px', size: '2px', stagger: 0 },
  { left: '20px', top: '17px', size: '1.5px', stagger: 90 },
  { left: '31px', top: '9px', size: '2.5px', stagger: 40 },
  { left: '41px', top: '19px', size: '1.5px', stagger: 150 },
  { left: '46px', top: '10px', size: '2px', stagger: 110 },
]
