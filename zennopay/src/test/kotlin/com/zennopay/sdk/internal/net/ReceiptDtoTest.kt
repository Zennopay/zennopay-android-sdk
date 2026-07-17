package com.zennopay.sdk.internal.net

import com.zennopay.sdk.internal.CurrencyDisplay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tolerant-decode tests for the receipt DTO (all four statuses incl. refunded,
 * plus wire-drift resilience) — the Android mirror of the iOS `ReceiptDTOTests`.
 * Robolectric supplies a real org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReceiptDtoTest {

    private fun decode(json: String): ReceiptDto = ReceiptDto.parse(JSONObject(json))

    private val capturedJson = """
        {
          "intent_id": "zp_abc123",
          "status": "captured",
          "merchant": { "name": "Ca Phe Sai Gon", "account_no": "•••• 0000", "bank_no": "970436", "country": "VN" },
          "amount_usd_cents": 14000,
          "local_amount_minor_units": 350000000,
          "local_currency": "704",
          "exchange_rate": 25000.0,
          "fees": { "margin_usd_cents": 210 },
          "corridor": "vn_vietqr",
          "transaction_ref": "demo_txn_000042",
          "created_at": "2026-07-18T04:07:11.000Z",
          "updated_at": "2026-07-18T04:07:41.000Z"
        }
    """.trimIndent()

    @Test
    fun `decode captured full shape`() {
        val r = decode(capturedJson)
        assertEquals("zp_abc123", r.intentId)
        assertEquals("captured", r.status)
        assertEquals(ReceiptStatus.CAPTURED, r.receiptStatus)
        assertEquals("Ca Phe Sai Gon", r.merchant?.name)
        assertEquals("•••• 0000", r.merchant?.accountNo)
        assertEquals("970436", r.merchant?.bankNo)
        assertEquals("VN", r.merchant?.country)
        assertEquals(14000L, r.amountUsdCents)
        assertEquals(350000000L, r.localAmountMinorUnits)
        assertEquals("704", r.localCurrency)
        assertEquals(25000.0, r.exchangeRate!!, 0.001)
        assertEquals(210L, r.fees?.marginUsdCents)
        assertEquals("vn_vietqr", r.corridor)
        assertEquals("demo_txn_000042", r.transactionRef)
        assertEquals("2026-07-18T04:07:11.000Z", r.createdAt)
        assertEquals("2026-07-18T04:07:41.000Z", r.updatedAt)
    }

    @Test
    fun `decode pending, non-terminal, sparse`() {
        val r = decode("""{"intent_id":"zp_1","status":"pending","amount_usd_cents":14000,"corridor":"vn_vietqr"}""")
        assertEquals(ReceiptStatus.PENDING, r.receiptStatus)
        assertFalse(r.receiptStatus!!.isTerminal)
        assertNull(r.merchant)
        assertNull(r.localAmountMinorUnits)
    }

    @Test
    fun `decode failed is terminal`() {
        val r = decode("""{"intent_id":"zp_1","status":"failed","amount_usd_cents":14000}""")
        assertEquals(ReceiptStatus.FAILED, r.receiptStatus)
        assertTrue(r.receiptStatus!!.isTerminal)
    }

    @Test
    fun `decode refunded is terminal and carries txn`() {
        val r = decode(
            """{"intent_id":"zp_1","status":"refunded","amount_usd_cents":14000,
               "merchant":{"name":"Ca Phe Sai Gon","account_no":"•••• 0000","bank_no":"970436","country":"VN"},
               "local_amount_minor_units":350000000,"local_currency":"704","transaction_ref":"demo_txn_9"}""",
        )
        assertEquals(ReceiptStatus.REFUNDED, r.receiptStatus)
        assertTrue(r.receiptStatus!!.isTerminal)
        assertEquals("demo_txn_9", r.transactionRef)
    }

    @Test
    fun `unknown status yields null receiptStatus`() {
        val r = decode("""{"intent_id":"zp_1","status":"authorized","amount_usd_cents":1}""")
        assertNull(r.receiptStatus)
    }

    @Test
    fun `tolerates alpha currency, string rate, and missing amount`() {
        val r = decode("""{"intent_id":"zp_1","status":"captured","local_currency":"VND","exchange_rate":"25000.5"}""")
        assertEquals("VND", r.localCurrency)
        assertEquals(25000.5, r.exchangeRate!!, 0.001)
        // A missing amount defaults to 0 rather than failing the whole decode.
        assertEquals(0L, r.amountUsdCents)
    }

    @Test
    fun `tolerates a non-numeric exchange rate`() {
        val r = decode("""{"intent_id":"zp_1","status":"captured","amount_usd_cents":10,"exchange_rate":"n/a"}""")
        assertNull(r.exchangeRate)
    }

    @Test
    fun `currency numeric normalization accepts alpha and numeric`() {
        assertEquals("704", CurrencyDisplay.numericCode("VND"))
        assertEquals("704", CurrencyDisplay.numericCode("704"))
        assertEquals("764", CurrencyDisplay.numericCode("thb"))
        assertEquals("840", CurrencyDisplay.numericCode("USD"))
        assertEquals("999", CurrencyDisplay.numericCode("999"))
        assertNull(CurrencyDisplay.numericCode(null))
        assertNull(CurrencyDisplay.numericCode(""))
    }
}
