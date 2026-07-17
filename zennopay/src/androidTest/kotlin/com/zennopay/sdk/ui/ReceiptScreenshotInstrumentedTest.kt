package com.zennopay.sdk.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zennopay.sdk.debug.ZennopayDebugGallery
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the `presentReceipt` receipt screen through the real receipt-DTO
 * render path (the debug gallery, NO network / NO money) and writes a PNG to the
 * app's external files dir so the QA pass can pull a real-device screenshot.
 */
@RunWith(AndroidJUnit4::class)
class ReceiptScreenshotInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private fun shoot(spec: String, name: String) {
        rule.setContent { ZennopayDebugGallery.Root(spec = spec) }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.getExternalFilesDir(null)!!
        val out = File(dir, name)
        FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("RECEIPT SHOT -> ${out.absolutePath} (${out.length()} bytes)")
        assertTrue("screenshot looks empty", out.length() > 8000)
    }

    @Test
    fun capturesCapturedReceipt() {
        shoot("rcpt:vnd35", "receipt-captured.png")
        rule.onNodeWithText("Payment successful").assertExists()
    }

    @Test
    fun capturesRefundedReceipt() {
        shoot("refunded:vnd35", "receipt-refunded.png")
        rule.onNodeWithText("Payment refunded").assertExists()
    }
}
