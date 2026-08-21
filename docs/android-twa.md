# Publishing Vernfy to Google Play as a TWA

_Status: **prepared, not published.** Everything in this repo that a TWA needs is verified working;
what remains needs a Play Console account and a signing key, which are yours, not the repo's._

A **Trusted Web Activity** is an Android app whose entire content is this site, rendered by the
user's own Chrome without a browser UI. There is no second codebase and no separate release: a
`docker compose` deploy updates the Play Store app too, because the app *is* the deployed site.
That is what makes it the cheap path — and also its limit, which §4 is honest about.

## 1. What must already be true (all verified, 2026-08-21)

| Requirement | State |
|---|---|
| HTTPS, valid certificate | ✅ Cloudflare edge + Caddy origin |
| Web app manifest with `name`, `short_name`, `icons` (incl. 512 maskable), `display: standalone` | ✅ `web/public/manifest.json`, asserted by `web/src/pwa.test.ts` |
| A service worker | ✅ `web/public/sw.js` |
| `screenshots` (for the Play listing's own quality signals) | ✅ narrow + wide |
| `/.well-known/` reachable from the site root | ✅ routed to the SPA container; Vite copies `web/public/.well-known/` into `dist/` (checked, dot-directories included) |

## 2. Build the app

Bubblewrap generates and signs the Android project. Run it wherever you keep the keystore.

```bash
npm i -g @bubblewrap/cli
bubblewrap init --manifest https://vernfy.com/manifest.json
bubblewrap build          # produces app-release-bundle.aab + app-release-signed.apk
```

`bubblewrap init` creates `android.keystore` on first run if you let it. **That keystore is the
app's identity for its entire life on Play** — lose it and the listing cannot be updated by
anyone, ever, including you. Back it up somewhere that is not this machine. `.gitignore` already
excludes `*.keystore`, `*.jks`, `*.aab` and `*.apk`, so it cannot be committed by accident.

## 3. Link the app to the site

Chrome only drops the URL bar if the site vouches for the app. Get the signing fingerprint:

```bash
keytool -list -v -keystore android.keystore -alias android | grep 'SHA256:'
```

Then create `web/public/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.vernfy.twa",
    "sha256_cert_fingerprints": ["<the SHA256 from keytool, colon-separated hex>"]
  }
}]
```

Deploy, then **verify it is really being served**:

```bash
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36'
curl -sI -A "$UA" https://vernfy.com/.well-known/assetlinks.json | grep -i content-type
```

> ⚠️ **A missing file here does not 404.** nginx serves the SPA with
> `try_files $uri $uri/ /index.html`, so a wrong path returns **index.html with a 200** — and
> Chrome then fails verification with no clue why. The check that matters is
> `content-type: application/json`; `text/html` means the file is not where you think it is.
> (Measured before this doc was written: `/.well-known/assetlinks.json` returned `text/html`,
> which is exactly what "the file does not exist yet" looks like.)

Google also serves a verification view of what it sees:
`https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://vernfy.com&relation=delegate_permission/common.handle_all_urls`

## 4. Upload, and what it costs

Play Console: **$25 once**, then create the app, upload the `.aab`, fill the store listing
(the manifest `screenshots` are a good starting point but Play wants its own sizes), and submit.
Review is typically days.

**Two things to know before starting:**

- **Play now requires a closed test with 12 testers for 14 days** before a *personal* developer
  account can publish publicly. An organisation account skips this. Budget for it — it is the
  longest part of the process by far, and it is a policy, not a technical step.
- **Updates that change the manifest or the linked origin need a new upload**; ordinary code
  deploys do not, because the app just loads the site.

## 5. iOS is not the same story

There is no TWA equivalent. A wrapper whose only content is a website is refused under
**App Store Review Guideline 4.2 (minimum functionality)** unless it adds something a browser
cannot do. Getting on the App Store therefore means Capacitor (or similar) *plus* real native
value — biometric unlock, widgets, native notifications — and **$99/year**, ongoing.

Until then, iOS users install the same app from Safari via **Share → Add to Home Screen**, which
the app now prompts for (`web/src/components/InstallBanner.tsx`). That path already delivers the
standalone window, the icon, and web push (iOS 16.4+ delivers push only to a home-screen web app),
which is most of what a wrapper would have bought.
