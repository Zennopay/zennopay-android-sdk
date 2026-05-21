package com.zennopay.sdk

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.zennopay.sdk.internal.UrlBuilder

/**
 * Activity that catches the deep-link return from the checkout web.
 *
 * The partner application registers an intent-filter for this Activity in
 * their own AndroidManifest (we cannot do it here because the scheme is
 * partner-defined, e.g. `wizz://payment-result`). Example:
 *
 * ```xml
 * <activity
 *     android:name="com.zennopay.sdk.ZennopayCallbackActivity"
 *     android:exported="true"
 *     android:launchMode="singleTask"
 *     android:theme="@android:style/Theme.NoDisplay">
 *   <intent-filter>
 *     <action android:name="android.intent.action.VIEW"/>
 *     <category android:name="android.intent.category.DEFAULT"/>
 *     <category android:name="android.intent.category.BROWSABLE"/>
 *     <data android:scheme="wizz" android:host="payment-result"/>
 *   </intent-filter>
 * </activity>
 * ```
 *
 * Flow:
 *   1. The browser redirects to `wizz://payment-result?intent_id=...&status=...`.
 *   2. Android routes that intent to this activity.
 *   3. We parse the Uri via [UrlBuilder.parseReturnUrl].
 *   4. We hand the [PaymentResult] to [Zennopay.dispatchResult], which fires
 *      the listener the partner registered when calling [Zennopay.openCheckout].
 *   5. We finish() immediately so the partner's activity returns to the
 *      foreground; the no-display theme avoids any flash.
 */
class ZennopayCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    private fun handleIntent() {
        val data = intent?.data
        if (data == null) {
            Log.w(TAG, "Callback launched without intent data; ignoring")
            Zennopay.dispatchResult(PaymentResult.Failure(ZennopayError.ReturnUrlMalformed))
            finish()
            return
        }

        val result = UrlBuilder.parseReturnUrl(data)
        Zennopay.dispatchResult(result)
        finish()
    }

    private companion object {
        const val TAG = "ZennopayCallback"
    }
}
