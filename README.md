# Zennopay Android SDK

Official Android SDK for [Zennopay](https://zennopay.com) cross-border QR
payments. Stripe-Checkout-style: the SDK opens the Zennopay checkout web
inside a Chrome Custom Tab (system browser tab), then receives the result via
a partner-defined deep-link.

- **Module:** `:zennopay`
- **Maven coordinates:** `com.zennopay:sdk:0.1.0`
- **`minSdk` 24, `compileSdk` 34**
- No WebView, no embedded surface. The user always sees the real
  `https://checkout.zennopay.com` URL in a system-rendered address bar.

Full documentation: [docs.zennopay.com](https://docs.zennopay.com)

---

## Why Custom Tabs (and not a WebView)

| | Custom Tabs | WebView |
|---|---|---|
| Real address bar visible to user | yes | no |
| Cookies isolated from partner app | yes | no |
| Partner app can script the page | no | yes |
| PCI / compliance posture | clean | murky |

We use the system browser for the same reason Stripe Checkout, Plaid Link, and
3-D Secure prompts do.

---

## Installation

### Gradle

The SDK is not yet on a public Maven repo. Until then, publish to
`mavenLocal()`:

```bash
git clone https://github.com/amanpal108/zennopay-android-sdk
cd zennopay-android-sdk
./gradlew :zennopay:publishToMavenLocal
```

Then in the partner app's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal() // until we publish to a remote
    }
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.zennopay:sdk:0.1.0")
}
```

### Register the deep-link callback activity

The SDK cannot register its own intent-filter because the return URL scheme
is partner-defined (Wizz uses `wizz://payment-result`). Add the following to
the partner app's `AndroidManifest.xml` inside the `<application>` element:

```xml
<activity
    android:name="com.zennopay.sdk.ZennopayCallbackActivity"
    android:exported="true"
    android:launchMode="singleTask"
    android:theme="@android:style/Theme.NoDisplay">
  <intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="wizz" android:host="payment-result"/>
  </intent-filter>
</activity>
```

If you use a different scheme, change both the `<data android:scheme="…">`
attribute and the `returnScheme` argument to `openCheckout`.

---

## Usage

```kotlin
import androidx.activity.ComponentActivity
import com.zennopay.sdk.PaymentResult
import com.zennopay.sdk.PaymentStatus
import com.zennopay.sdk.Zennopay
import com.zennopay.sdk.ZennopayError

class CheckoutActivity : ComponentActivity() {

    private fun startPayment(intentId: String, jwt: String) {
        Zennopay.openCheckout(
            activity = this,
            intentId = intentId,
            jwt = jwt,
            returnScheme = "wizz",
        ) { result ->
            when (result) {
                is PaymentResult.Success -> when (result.status) {
                    PaymentStatus.SUCCESS  -> showReceipt(result.intentId)
                    PaymentStatus.PENDING  -> showPending(result.intentId)
                    PaymentStatus.FAILED   -> showFailure()
                    PaymentStatus.CANCELED -> showCanceled()
                }
                is PaymentResult.Failure -> when (val e = result.error) {
                    ZennopayError.InvalidJwt              -> reportSdkBug("bad JWT")
                    ZennopayError.ReturnUrlMalformed      -> reportSdkBug("bad return URL")
                    ZennopayError.UserCanceled            -> showCanceled()
                    ZennopayError.CustomTabsUnavailable   -> showInstallBrowserPrompt()
                    is ZennopayError.NetworkError         -> showRetry(e.cause)
                }
            }
        }
    }
}
```

### What the SDK does under the hood

1. Decodes the JWT and verifies its `intent_id` claim matches the
   `intentId` argument. A mismatch raises `ZennopayError.InvalidJwt`
   immediately — the checkout never opens.
2. Builds `https://checkout.zennopay.com/flow/<intentId>/scan#token=<jwt>`.
   The JWT is in the URL fragment so it never reaches the server in a Referer
   header or proxy log.
3. Probes for a Custom Tabs provider (Chrome by default). If found, launches
   `CustomTabsIntent`. If not, falls back to a plain `ACTION_VIEW` and logs a
   warning.
4. Stashes the partner-supplied callback in a single-slot listener.
5. When the checkout web redirects to `wizz://payment-result?intent_id=…&status=…`,
   Android routes the intent to `ZennopayCallbackActivity`, which parses the
   query string and invokes the callback exactly once.

---

## Public API

| Symbol | Notes |
|---|---|
| `Zennopay.openCheckout(activity, intentId, jwt, returnScheme, onResult)` | Single entry point. |
| `Zennopay.isCustomTabsAvailable(activity)` | Optional pre-flight check. |
| `PaymentResult.Success(intentId, status)` | Happy path. |
| `PaymentResult.Failure(error)` | All errors. |
| `PaymentStatus` | `SUCCESS, FAILED, CANCELED, PENDING`. |
| `ZennopayError` | `InvalidJwt, ReturnUrlMalformed, UserCanceled, CustomTabsUnavailable, NetworkError(cause)`. |

---

## Testing

The pure URL + JWT logic lives in `com.zennopay.sdk.internal` and is covered
by JUnit 4 + Robolectric (since `android.net.Uri` is required to parse the
return deep-link).

```bash
./gradlew :zennopay:test
```

## License

MIT — see [LICENSE](LICENSE).
