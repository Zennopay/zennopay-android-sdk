# Changelog

All notable changes to the Zennopay Android SDK are documented here.

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
