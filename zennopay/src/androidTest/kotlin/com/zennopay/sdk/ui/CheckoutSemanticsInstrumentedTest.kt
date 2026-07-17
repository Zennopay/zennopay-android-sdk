package com.zennopay.sdk.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zennopay.sdk.debug.ZennopayDebugGallery
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke pass of the accessibility contract (the full matrix runs on
 * the JVM in `AccessibilitySemanticsTest`; this keeps the connected suite
 * exercising the same semantics on real Android). Mock state only — NO
 * network, NO money movement.
 */
@RunWith(AndroidJUnit4::class)
class CheckoutSemanticsInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun reviewBreakdownRowOpensSheet() {
        rule.setContent { ZennopayDebugGallery.Root(spec = "review:vnd35") }
        rule.onNodeWithTag("zp.review.breakdown").assertHasClickAction()
        rule.onNodeWithTag("zp.review.breakdown").performClick()
        rule.onNodeWithText("Payment breakdown").assertIsDisplayed()
        rule.onNodeWithText("Zero-fee launch pricing").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close breakdown").assertIsDisplayed()
    }

    @Test
    fun slideToPayHasTalkBackActivationFallback() {
        var confirmed = 0
        rule.setContent {
            SlideToConfirm(label = "Slide to pay", onConfirm = { confirmed++ })
        }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("zp.slide.track")
            .assertContentDescriptionEquals("Slide to pay")
        rule.onNodeWithTag("zp.slide.track")
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, confirmed)
    }

    @Test
    fun keypadRefusesOverLimitWithHint() {
        rule.setContent { ZennopayDebugGallery.Root(spec = "keypad") }
        repeat(7) { rule.onNodeWithContentDescription("9").performClick() }
        rule.onNodeWithTag("zp.keypad.limitHint").assertIsDisplayed()
        rule.onNodeWithTag("zp.amount.entry")
            .assertContentDescriptionEquals("Amount: 999,999 VND")
    }

    @Test
    fun poweredByFooterIsGrouped() {
        rule.setContent { ZennopayDebugGallery.Root(spec = "receipt:vnd35") }
        rule.onNodeWithTag("zp.poweredBy")
            .assertContentDescriptionEquals("Powered by Zennopay")
        rule.onNodeWithContentDescription("Share receipt").assertHasClickAction()
    }
}
