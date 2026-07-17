package com.zennopay.sdk.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zennopay.sdk.ui.ZennopayAppearance

/**
 * DEBUG-only host for [ZennopayDebugGallery] (compiled out of release
 * artifacts — `src/debug` only). Drives the accessibility / overflow /
 * screen-size QA passes:
 *
 * ```
 * adb shell am start -n <pkg>/com.zennopay.sdk.debug.ZennopayDebugGalleryActivity \
 *     -e spec review:vndhuge [-e mode dark]
 * ```
 */
class ZennopayDebugGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val spec = intent.getStringExtra("spec") ?: "review"
        val appearance = when (intent.getStringExtra("mode")?.lowercase()) {
            "dark" -> ZennopayAppearance(mode = ZennopayAppearance.Mode.Dark)
            "light" -> ZennopayAppearance(mode = ZennopayAppearance.Mode.Light)
            else -> ZennopayAppearance.Automatic
        }
        setContent {
            ZennopayDebugGallery.Root(spec = spec, appearance = appearance)
        }
    }
}
