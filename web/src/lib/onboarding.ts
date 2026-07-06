// New-account detection + first-run tour gating.
//
// A brand-new account should ALWAYS get the guided tour immediately after it lands on the
// dashboard — even on a browser that has already seen the tour with a different account.
// We record the moment of registration and treat an account created within a short window
// as "new" (creation time ≈ current time), which is exactly the signal we want.

const SIGNUP_AT = 'vernfy_signup_at'
const TOUR_SEEN = 'vernfy_onboarding_v1'
const NEW_ACCOUNT_WINDOW_MS = 10 * 60 * 1000 // 10 minutes

/** Call right after a successful registration — stamps when this account was created. */
export function markAccountCreated(): void {
  try {
    localStorage.setItem(SIGNUP_AT, String(Date.now()))
  } catch {
    // storage blocked — non-fatal
  }
}

/** True when the current account was created just now (its creation time ≈ the current time). */
export function isNewAccount(): boolean {
  try {
    const at = Number(localStorage.getItem(SIGNUP_AT))
    return at > 0 && Date.now() - at < NEW_ACCOUNT_WINDOW_MS
  } catch {
    return false
  }
}

/** Auto-open the tour for a brand-new account, or the first time ever on this browser. */
export function shouldAutoOpenTour(): boolean {
  try {
    return isNewAccount() || !localStorage.getItem(TOUR_SEEN)
  } catch {
    return false
  }
}

/** Call when the tour is finished/skipped so it does not auto-open again. */
export function markTourSeen(): void {
  try {
    localStorage.setItem(TOUR_SEEN, '1')
    localStorage.removeItem(SIGNUP_AT)
  } catch {
    // non-fatal
  }
}
