# Changelog

All notable changes to the Zennopay Android SDK are documented here.

## 0.5.0 - 2026-07-18

Version-aligned across all SDKs; API domain zennopay.com → zennopay.in
(canonical). No API changes.

## 0.3.0 - 2026-07-18

Reopen the authoritative receipt.

### Added

- **`Zennopay.presentReceipt(...)`** (and the Compose
  `rememberZennopayReceiptLauncher`) — reopen the **authoritative Zennopay
  receipt** for a *past* payment, with live pending/refund status, without
  moving any money. Your backend mints a short-lived RS256 **receipt token**
  (`aud = zennopay-receipt`, `sub = partner_user_id`, ≤15-min exp, reusable
  for polling); the SDK sends it as `Authorization: Bearer` on
  `GET /v1/payment_intents/{id}/receipt` and renders the same terminal
  screens as checkout:
  - `captured` / `refunded` → the receipt screen (refunded carries "refunded
    to your wallet" messaging);
  - `failed` → the failure screen;
  - `pending` → the pending detail, polling until it resolves (respecting the
    poll interval/timeout; a 401 mid-poll re-mints via `refreshReceiptToken`).
- Tolerant receipt parsing: all four statuses, an alpha *or* numeric currency
  (`VND`/`704`), a numeric *or* string exchange rate, and missing display
  fields never fail the decode — the backend stays authoritative.

`presentReceipt` is fully independent of `presentCheckout`, which is
unchanged and source-compatible. Depend on it with
`implementation("in.zennopay:sdk:0.5.0")`.

## 0.2.1 - 2026-07-18

Accessibility + fee transparency.

### Packaging

- **Now on Maven Central** as `in.zennopay:sdk:0.2.1`. The Maven coordinate
  uses the reverse-DNS namespace of the company domain `zennopay.in`
  (`in.zennopay`), replacing the internal `com.zennopay` placeholder that
  only ever published to GitHub Packages. The Kotlin import package is
  unchanged — you still `import com.zennopay.sdk.*`; only the dependency
  coordinate changed. Depend on it with
  `implementation("in.zennopay:sdk:0.2.1")`.

### Added

- **Payment breakdown sheet** — tapping the review screen's "You'll pay
  exactly" row opens the full cost breakdown: what the merchant receives,
  the locked exchange rate, subtotal, the convenience fee ($0.00 under the
  zero-fee launch pricing), and the exact total. The merchant always receives
  the exact QR amount; no hidden margin is added to the rate.
- **"Powered by Zennopay" footer** on every screen, with light/dark wordmark
  variants (white over the camera surface). Not overridable via
  `ZennopayAppearance`.
- **Debug screen gallery** (`debug` builds only): render any screen with mock
  state — no network, no camera, no money movement.

### Accessibility

- Font-scale caps: text follows the system font size, clamped (~1.65× for
  text, ~1.35× for the large amount numerals) so the keypad and slide-to-pay
  always stay reachable; amount heroes auto-shrink instead of clipping.
- TalkBack: slide-to-pay is activatable by double-tap (sliding is not
  required), every scanner/keypad/receipt control carries a human label and
  role, money labels read naturally ("US dollar equivalent $140.00"), and the
  breakdown rows read as single elements.
- Touch targets are at least 48 dp throughout.
- Reduced motion (animations off) disables the scan-line sweep, the keypad's
  refusal shake, and the slide spring-back.
- Keypad input policy: keys that would exceed ₫5,000,000 per payment (or the
  9-digit ceiling) are refused with a gentle shake and a plain-language hint —
  the amount can never overflow the screen.
- WCAG AA contrast for fine print in both light and dark mode.

### Changed

- Unit test suite grew from 89 to 132 tests (input policy, type-scale caps,
  currency overflow guards, and Compose semantics-tree accessibility tests),
  plus an on-device instrumented smoke suite.

## 0.2.0 - 2026-07-17

The **PaymentSheet** release: the SDK now renders the entire pay experience
natively, in-process — no browser tab, no deep-link round trip.

### Added

- `Zennopay.presentCheckout(...)` — full-screen native checkout Activity:
  QR scan (CameraX + ML Kit, on-device) with corridor branding →
  local-currency-primary review with USD quote → slide-to-pay → processing /
  pending / receipt states.
- `rememberZennopayLauncher(...)` — Compose-first entry point returning a
  stable `present(intentId, sessionJwt)` lambda.
- `ZennopayAppearance` theming: colors (light/dark), corner radii
  (clamped ≤ 12 dp), typography, primary button, optional header logo.
- `ZennopayConfig` environments (`STAGING`, `PRODUCTION`, custom base URL);
  the environment is a value, not a code path.
- `PaymentResult.Pending` — status polling timed out but the payment may
  still settle; reconcile via webhook or the intent API.
- `refreshSession` host hook — the SDK asks the host to re-mint the session
  JWT for the same intent on mid-flow expiry (401).
- Durable idempotency-key persistence (DataStore) — confirm fires exactly
  once across retries and process death.
- Paste-QR fallback when the camera permission is denied or unavailable.
- 89 unit tests (JUnit 4 + Robolectric).

### Removed (breaking)

- The hosted-checkout flow: `Zennopay.openCheckout(...)`,
  `Zennopay.isCustomTabsAvailable(...)`, `ZennopayCallbackActivity`, and the
  partner deep-link contract. Remove the callback activity's intent-filter
  from your manifest; results now arrive in-process via the callback.

## 0.1.0 - 2026-05-21

Initial public release.

### Added

- `Zennopay.openCheckout(...)` — Stripe-Checkout-style payment handoff via
  Chrome Custom Tabs. Token rides in the URL fragment, never sent to the
  server in headers or proxy logs.
- **JWT → intent_id binding**: the SDK now decodes the JWT and verifies its
  `intent_id` claim matches the `intentId` argument before launching the
  Custom Tab. A mismatch raises `ZennopayError.InvalidJwt` immediately,
  preventing a malformed call site from leaking a token scoped to a different
  intent.
- `Zennopay.isCustomTabsAvailable(activity)` pre-flight check.
- `ZennopayCallbackActivity` for receiving the partner-defined return
  deep-link.
- Typed `PaymentResult` / `PaymentStatus` / `ZennopayError` sealed types.
