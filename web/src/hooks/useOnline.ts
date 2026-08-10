import { useEffect, useState } from 'react'

/**
 * Whether the browser currently believes it has a network.
 *
 * `navigator.onLine` is a weak signal — it reports the link, not whether the API is reachable, so
 * it can say true on a captive-portal wifi. It is still the right one for the offline banner: the
 * banner's job is to explain figures that may be stale, and being told "you appear to be offline"
 * a moment early is far better than a screen of failed requests with no explanation.
 */
export function useOnline() {
  const [online, setOnline] = useState(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine !== false,
  )

  useEffect(() => {
    const goOnline = () => setOnline(true)
    const goOffline = () => setOnline(false)
    // No re-read here on purpose: the initialiser above already took the current value, and these
    // two events cover every change after it. Setting state again would cost a render on every
    // mount to close a gap of a few microseconds.
    window.addEventListener('online', goOnline)
    window.addEventListener('offline', goOffline)
    return () => {
      window.removeEventListener('online', goOnline)
      window.removeEventListener('offline', goOffline)
    }
  }, [])

  return online
}
