# Changelog

All notable changes to the Zennopay Android SDK are documented here.

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
