/**
 * Helpers for loading a screen out of several requests without letting one failure blank it.
 *
 * Every page used `Promise.all`, which rejects as soon as any one call does — so a single cache
 * miss offline threw away the three answers that *had* arrived. The visible result was a page with
 * no categories to pick from, which reads as "the app is broken" rather than "one figure is
 * missing". `Promise.allSettled` plus these two helpers keeps whatever came back.
 */

/** The value if it arrived, the fallback if it did not. */
export function valueOr<T>(result: PromiseSettledResult<T>, fallback: T): T {
  return result.status === 'fulfilled' ? result.value : fallback
}

/**
 * The failure worth telling the user about, or null when there is none.
 *
 * Offline, a partial failure is expected and already explained by the offline banner — saying it
 * again as an error would tell the user something is wrong when the app is doing exactly what it
 * promised. Offline with *nothing* loaded is different: there is no page to look at, so say so.
 * Online, any failure is still surfaced, unchanged.
 */
export function loadFailure(
  results: readonly PromiseSettledResult<unknown>[],
  online: boolean,
): unknown | null {
  const failures = results.filter(
    (result): result is PromiseRejectedResult => result.status === 'rejected',
  )
  if (failures.length === 0) return null
  if (!online && failures.length < results.length) return null
  return failures[0].reason
}
