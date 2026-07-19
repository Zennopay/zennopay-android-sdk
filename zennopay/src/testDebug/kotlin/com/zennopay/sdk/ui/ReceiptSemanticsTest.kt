package com.zennopay.sdk.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.zennopay.sdk.debug.ZennopayDebugGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Semantics-tree render assertions for the `presentReceipt` screens, run on the
 * JVM under Robolectric (no emulator, no network, no money). Drives the debug
 * gallery's receipt-DTO render path (`rcpt` = captured, `refunded` = refund copy)
 * plus the standalone loading screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `captured receipt renders through the receipt flow with share and done`() {
        rule.setContent { ZennopayDebugGallery.Root(spec = "rcpt:vnd35") }
        rule.onNodeWithText("Payment successful").assertIsDisplayed()
        rule.onNodeWithText("3,500,000 VND").assertIsDisplayed()
        rule.onNodeWithContentDescription("Share receipt").assertHasClickAction()
        rule.onNodeWithTag("zp.receipt.done").assertHasClickAction()
        rule.onNodeWithTag("zp.poweredBy").assertIsDisplayed()
    }

    @Test
    fun `refunded receipt shows the refund copy`() {
        rule.setContent { ZennopayDebugGallery.Root(spec = "refunded:vnd35") }
        rule.onNodeWithText("Payment refunded").assertIsDisplayed()
        rule.onNodeWithTag("zp.receipt.refunded").assertIsDisplayed()
        rule.onNodeWithTag("zp.receipt.done").assertHasClickAction()
    }

    @Test
    fun `receipt loading screen announces loading`() {
        rule.setContent { ReceiptLoadingScreen() }
        rule.onNodeWithTag("zp.receipt.loading").assertIsDisplayed()
        rule.onNodeWithText("Loading your receipt…").assertIsDisplayed()
    }
}
