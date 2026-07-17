# Zennopay Android SDK

The Android SDK for [Zennopay](https://zennopay.in) — let your app's users
scan local merchant QR codes abroad and pay from their wallet balance.

The SDK presents the **PaymentSheet**: the full native pay experience — QR
scan → amount + FX quote → slide-to-pay → result — as its own full-screen
Activity over your app. The UI is Jetpack Compose; scanning is CameraX +
ML Kit, entirely on-device. Your app makes one call and gets exactly one
`PaymentResult` back.

Full documentation: [Zennopay/zennopay-docs](https://github.com/Zennopay/zennopay-docs)

## Requirements

- `minSdk` 24 (Android 7.0), `compileSdk` 34
- Kotlin 1.9+, AndroidX
- Your app does **not** need to use Compose — the sheet runs in its own
  Activity. A Compose-first entry point (`rememberZennopayLauncher`) is
  provided for hosts that are.
- A backend session endpoint that creates the payment intent and mints the
  short-lived session JWT (your API keys never ship in the app)

## Installation

Published to **Maven Central** as `in.zennopay:sdk`. Make sure
`mavenCentral()` is in your repositories, then add the dependency.

Gradle Kotlin DSL (`build.gradle.kts`):

```kotlin
dependencies {
    implementation("in.zennopay:sdk:0.3.0")
}
```

Gradle Groovy (`build.gradle`):

```groovy
dependencies {
    implementation 'in.zennopay:sdk:0.3.0'
}
```

> **Namespace note:** the Maven coordinate is `in.zennopay:sdk` (the
> reverse-DNS form of the company domain `zennopay.in`), but the Kotlin
> import package is unchanged — you still `import com.zennopay.sdk.*`. The
> Gradle group and the Kotlin package are intentionally independent:
>
> ```kotlin
> // dependency coordinate
> implementation("in.zennopay:sdk:0.3.0")
> // …but the imports stay:
> import com.zennopay.sdk.Zennopay
> import com.zennopay.sdk.PaymentResult
> ```

### Camera permission

The SDK's manifest merges in the `CAMERA` permission (with the camera marked
as an optional feature) and requests it at runtime when the scanner opens —
you add nothing to your manifest. If the user denies the permission, the
sheet degrades to a paste-QR fallback; the flow is always completable
without a camera.

## Quickstart

Fetch a checkout session (intent id + session JWT) from your backend, then
present the sheet.

### Compose hosts

```kotlin
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.ZennopayConfig
import com.zennopay.sdk.rememberZennopayLauncher

@Composable
fun PayButton(session: CheckoutSession, viewModel: WalletViewModel) {
    val present = rememberZennopayLauncher(
        config = ZennopayConfig.STAGING,        // .PRODUCTION for live traffic
        refreshSession = { intentId ->
            // Called on session expiry (401): re-mint for the SAME intent,
            // or return null if you can't.
            viewModel.remintSessionJwt(intentId)
        },
    ) { result ->
        when (result) {
            is PaymentResult.Completed -> viewModel.onPaid(result)
            is PaymentResult.Pending   -> viewModel.onPending(result) // may still settle — reconcile via webhook
            is PaymentResult.Canceled  -> Unit                        // no money moved
            is PaymentResult.Failed    -> viewModel.onPaymentFailed(result.error)
        }
    }

    Button(onClick = { present(session.intentId, session.sessionJwt) }) {
        Text("Scan & Pay")
    }
}
```

### Any `ComponentActivity`

```kotlin
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.Zennopay
import com.zennopay.sdk.ZennopayConfig

Zennopay.presentCheckout(
    activity = this,
    intentId = session.intentId,
    sessionJwt = session.sessionJwt,
    refreshSession = { id -> walletApi.remintSessionJwt(id) },
    config = ZennopayConfig.STAGING,
) { result ->
    when (result) {
        is PaymentResult.Completed -> showReceipt(result)
        is PaymentResult.Pending   -> showPending(result)
        is PaymentResult.Canceled  -> Unit
        is PaymentResult.Failed    -> showFailure(result.error)
    }
}
```

The SDK validates the JWT's structure, expiry, and intent binding
synchronously before launching anything; a token minted for a different
intent fails immediately with `Failed(IntentMismatch)`. Slide-to-pay fires
the confirm exactly once — the idempotency key is persisted before the
network call, so retries and process death can never double-debit.
`Pending` means status polling timed out before a terminal state: the
payment may still settle, so reconcile via your webhook or the intent API
rather than assuming failure.

### Reopen a receipt (`presentReceipt`)

Show the **authoritative Zennopay receipt** for a *past* payment — with live
pending/refund status — without moving any money. Your backend mints a
short-lived RS256 **receipt token** (`aud = zennopay-receipt`,
`sub = partner_user_id`, ≤15-min exp, reusable for polling) and hands it to
the app alongside the intent id. The SDK renders the same terminal screens as
checkout: a captured/refunded payment shows the receipt (refunded carries
refund messaging), a failed payment shows the failure screen, and a still-
processing payment shows the pending detail and polls until it resolves.

Compose:

```kotlin
import com.zennopay.sdk.rememberZennopayReceiptLauncher

@Composable
fun ReceiptButton(intentId: String, viewModel: WalletViewModel) {
    val presentReceipt = rememberZennopayReceiptLauncher(
        config = ZennopayConfig.STAGING,
        refreshReceiptToken = { id ->
            // Called on token expiry (401): re-mint for the same user, or null.
            viewModel.remintReceiptToken(id)
        },
        onDismiss = { /* the user closed the receipt */ },
    )
    Button(onClick = { presentReceipt(intentId, viewModel.receiptToken(intentId)) }) {
        Text("View receipt")
    }
}
```

Any `ComponentActivity`:

```kotlin
Zennopay.presentReceipt(
    activity = this,
    intentId = intentId,
    receiptToken = receiptTokenFromYourBackend,
    refreshReceiptToken = { id -> walletApi.remintReceiptToken(id) },
    config = ZennopayConfig.STAGING,
    onDismiss = { /* dismissed */ },
)
```

The receipt token is held in memory and sent as `Authorization: Bearer` on
`GET /v1/payment_intents/{id}/receipt` — no session JWT, no scan, no confirm.
`presentReceipt` is fully independent of `presentCheckout`, which is unchanged.

### Theming

`ZennopayAppearance` themes the sheet to your brand — colors, corner radii,
typography, primary button, and an optional header logo:

```kotlin
import com.zennopay.sdk.ui.ZennopayAppearance

val appearance = ZennopayAppearance(
    colors = ZennopayAppearance.Colors(primary = 0xFF1B4FD8),
    primaryButton = ZennopayAppearance.PrimaryButton(
        background = 0xFF1B4FD8,
        cornerRadius = 10.dp,
    ),
    logo = R.drawable.wordmark,
)
```

Pass nothing (`ZennopayAppearance.Automatic`) for the default Zennopay look
with system light/dark.

## Testing

```bash
./gradlew :zennopay:testDebugUnitTest
```

On an emulator, use the sheet's paste-QR fallback with any VietQR payload
string — the backend does the authoritative parse either way.

## Versioning

Zennopay SDKs follow [semver](https://semver.org). `v0.x` releases are
pre-GA: minor versions may contain breaking API changes, called out in the
[CHANGELOG](CHANGELOG.md).

All four Zennopay SDKs — [iOS](https://github.com/Zennopay/zennopay-ios-sdk),
Android, [Flutter](https://github.com/Zennopay/zennopay-flutter-sdk), and
[React Native](https://github.com/Zennopay/zennopay-react-native-sdk) — release
in lockstep: the same `vX.Y.Z` tag and GitHub Release is cut in each repo
per release. These standalone repos are release mirrors (squashed release
commits, not full development history); the mono development history is
internal.

## License

MIT — see [LICENSE](LICENSE).
