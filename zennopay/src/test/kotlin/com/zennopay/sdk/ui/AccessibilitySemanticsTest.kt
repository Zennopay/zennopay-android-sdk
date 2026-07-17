package com.zennopay.sdk.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.zennopay.sdk.debug.ZennopayDebugGallery
import com.zennopay.sdk.internal.CheckoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack / semantics-tree accessibility assertions for every PaymentSheet
 * screen, run on the JVM under Robolectric (no emulator, no network, no money).
 *
 * The pinned Compose (BOM 2024.06.00 → ui-test 1.6.x) has no
 * `enableAccessibilityChecks()` / ATF integration yet, so the a11y contract is
 * asserted directly on the semantics tree: roles, content descriptions, click
 * actions (the slide's TalkBack activation fallback), and merged money labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilitySemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(
        SemanticsProperties.Role, role,
    )

    // ---- Slide-to-pay: TalkBack activation fallback --------------------------

    @Test
    fun `slide-to-pay is a button with a click fallback that confirms`() {
        var confirmed = 0
        rule.setContent {
            SlideToConfirm(label = "Slide to pay", onConfirm = { confirmed++ })
        }
        val track = rule.onNodeWithTag("zp.slide.track")
        track.assert(hasRole(Role.Button))
        track.assertContentDescriptionEquals("Slide to pay")
        track.assertHasClickAction()
        // Freeze the clock before firing: the post-fire spinner is an infinite
        // animation, which would keep an auto-advancing clock busy forever.
        rule.mainClock.autoAdvance = false
        track.performSemanticsAction(SemanticsActions.OnClick)
        // A second TalkBack activation must be swallowed by the single-fire latch.
        track.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("TalkBack double-tap confirms without the drag", 1, confirmed)
    }

    @Test
    fun `disabled slide-to-pay is exposed as disabled`() {
        rule.setContent {
            SlideToConfirm(label = "Slide to pay", enabled = false, onConfirm = {})
        }
        rule.onNodeWithTag("zp.slide.track").assertIsNotEnabled()
    }

    @Test
    fun `confirming slide-to-pay announces processing and is disabled`() {
        // Freeze the clock: the confirming knob hosts an indeterminate spinner
        // (infinite animation).
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SlideToConfirm(label = "Slide to pay", confirming = true, onConfirm = {})
        }
        rule.mainClock.advanceTimeByFrame()
        val track = rule.onNodeWithTag("zp.slide.track")
        track.assertContentDescriptionEquals("Processing payment")
        track.assertIsNotEnabled()
    }

    // ---- Review screen -------------------------------------------------------

    private fun setGallery(spec: String) {
        rule.setContent { ZennopayDebugGallery.Root(spec = spec) }
    }

    @Test
    fun `review breakdown row is a labeled button that opens the sheet`() {
        setGallery("review:vnd35")
        val row = rule.onNodeWithTag("zp.review.breakdown")
        row.assert(hasRole(Role.Button))
        row.assertHasClickAction()
        row.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Payment breakdown").assertIsDisplayed()
        rule.onNodeWithText("Merchant receives").assertIsDisplayed()
        rule.onNodeWithText("Subtotal").assertIsDisplayed()
        rule.onNodeWithText("Convenience fee").assertIsDisplayed()
        rule.onNodeWithText("Zero-fee launch pricing").assertIsDisplayed()
        rule.onNodeWithText("$0.00").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close breakdown").assertIsDisplayed()
    }

    @Test
    fun `breakdown sheet math and rows for the demo amount`() {
        setGallery("breakdown:vnd35")
        rule.onNodeWithText("Payment breakdown").assertIsDisplayed()
        rule.onNodeWithText("3,500,000 VND").assertIsDisplayed()
        // Zero fee today: the bold total equals the subtotal.
        rule.onNodeWithText("You'll pay exactly").assertIsDisplayed()
        rule.onNodeWithTag("zp.breakdown.fee", useUnmergedTree = true)
            .assertTextEqualsExactly("$0.00")
    }

    @Test
    fun `review money labels read as human currency`() {
        setGallery("review:vnd35")
        rule.onNodeWithTag("zp.amount.usd")
            .assertContentDescriptionEquals("US dollar equivalent $140.00")
    }

    @Test
    fun `review shows the powered-by footer as one grouped element`() {
        setGallery("review:vnd35")
        rule.onNodeWithTag("zp.poweredBy")
            .assertContentDescriptionEquals("Powered by Zennopay")
    }

    // ---- Keypad --------------------------------------------------------------

    private fun setKeypad(reducedMotion: Boolean = true) {
        rule.setContent {
            KeypadScreen(
                state = CheckoutState.AmountEntry(rawQr = ZennopayDebugGallery.STATIC_DEMO_QR),
                corridor = "vn_vietqr",
                reducedMotion = reducedMotion,
                onEvent = {},
            )
        }
    }

    private fun tap(description: String, times: Int = 1) {
        repeat(times) {
            rule.onNodeWithContentDescription(description).performClick()
        }
        rule.waitForIdle()
    }

    @Test
    fun `keypad keys expose digit, triple zero and delete labels`() {
        setKeypad()
        for (d in '0'..'9') {
            rule.onNodeWithContentDescription(d.toString()).assertHasClickAction()
        }
        rule.onNodeWithContentDescription("triple zero").assertHasClickAction()
        rule.onNodeWithContentDescription("delete").assertHasClickAction()
    }

    @Test
    fun `keypad hero announces the entered amount`() {
        setKeypad()
        rule.onNodeWithTag("zp.amount.entry")
            .assertContentDescriptionEquals("Amount, not yet entered")
        tap("3")
        tap("5")
        tap("triple zero")
        rule.onNodeWithTag("zp.amount.entry")
            .assertContentDescriptionEquals("Amount: 35,000 VND")
    }

    @Test
    fun `over-limit keypress surfaces the 5M hint and refuses the digit`() {
        setKeypad()
        tap("9", times = 7) // 7th nine would be ₫9,999,999 — over the cap
        rule.onNodeWithTag("zp.keypad.limitHint").assertIsDisplayed()
        rule.onNodeWithText("The limit is ₫5,000,000 per payment.").assertIsDisplayed()
        rule.onNodeWithTag("zp.amount.entry")
            .assertContentDescriptionEquals("Amount: 999,999 VND")
        // Backspace clears the hint.
        tap("delete")
        rule.onNodeWithTag("zp.keypad.limitHint").assertDoesNotExistCompat()
    }

    // ---- Scanner -------------------------------------------------------------

    @Test
    fun `scanner controls carry human labels and button roles`() {
        rule.setContent {
            ScannerScreen(
                scanning = CheckoutState.Scanning(cameraAvailable = false),
                checking = false,
                corridor = "vn_vietqr",
                reducedMotion = true,
                onEvent = {},
            )
        }
        rule.onNodeWithContentDescription("Close").assertHasClickAction()
        rule.onNodeWithContentDescription("Choose a QR from your photos")
            .assert(hasRole(Role.Button))
        rule.onNodeWithTag("zp.scan.pasteCode")
            .assert(hasRole(Role.Button))
        rule.onNodeWithContentDescription("Turn flashlight on").assertIsNotEnabled()
        rule.onNodeWithTag("zp.scan.help").assert(hasRole(Role.Button))
    }

    // ---- Terminal screens ----------------------------------------------------

    @Test
    fun `receipt exposes share and done controls`() {
        setGallery("receipt:vnd35")
        rule.onNodeWithContentDescription("Share receipt").assertHasClickAction()
        rule.onNodeWithTag("zp.receipt.done").assertHasClickAction()
        rule.onNodeWithText("Payment successful").assertIsDisplayed()
        rule.onNodeWithTag("zp.poweredBy")
            .assertContentDescriptionEquals("Powered by Zennopay")
    }

    @Test
    fun `failure screen keeps done reachable`() {
        setGallery("failure:vnd35")
        rule.onNodeWithText("Payment failed").assertIsDisplayed()
        rule.onNodeWithText("Done").assertHasClickAction()
    }

    // ---- Font-scale caps -----------------------------------------------------

    @Test
    fun `tabular style caps regular text at the accessibility-medium multiplier`() {
        var captured: TextUnit = TextUnit.Unspecified
        rule.setContent {
            WithFontScale(2f) {
                captured = tabularStyle(16.sp, ZType.regular, ZColors.palette().text).fontSize
            }
        }
        // Effective on-screen scale = fontSize × fontScale = 16 × cap.
        assertEquals(16f * ZTypeScale.REGULAR_MAX_MULTIPLIER, captured.value * 2f, 0.1f)
    }

    @Test
    fun `tabular style caps hero numerals earlier`() {
        var captured: TextUnit = TextUnit.Unspecified
        rule.setContent {
            WithFontScale(2f) {
                captured = tabularStyle(
                    56.sp, ZType.bold, ZColors.palette().text, hero = true,
                ).fontSize
            }
        }
        assertEquals(56f * ZTypeScale.HERO_MAX_MULTIPLIER, captured.value * 2f, 0.35f)
    }

    @Test
    fun `tabular style leaves moderate scales untouched`() {
        var captured: TextUnit = TextUnit.Unspecified
        rule.setContent {
            WithFontScale(1.3f) {
                captured = tabularStyle(16.sp, ZType.regular, ZColors.palette().text).fontSize
            }
        }
        assertEquals(16f, captured.value, 0.01f)
        assertTrue(captured != TextUnit.Unspecified)
    }

    @Composable
    private fun WithFontScale(scale: Float, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale = scale),
        ) {
            content()
        }
    }
}

/** `assertDoesNotExist` with a stable local name (avoids shadowing warnings). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() =
    assertDoesNotExist()

/** Exact single-text assertion (the node renders one text value). */
private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsExactly(
    expected: String,
) = assert(
    SemanticsMatcher("text == $expected") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == expected
    },
)
