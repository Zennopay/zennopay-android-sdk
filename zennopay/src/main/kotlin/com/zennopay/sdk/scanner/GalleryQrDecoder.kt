package com.zennopay.sdk.scanner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Decode a QR from a picked gallery image (spec §1.1 — gallery is a first-class
 * camera-free fallback). Uses the SAME on-device ML Kit decoder as the live
 * scanner, then applies the display-only [QrPayload] gate so the raw payload
 * still goes through the authoritative backend `/scan` parse. Never interprets
 * the QR for money movement.
 *
 * Returns the first plausible EMVCo merchant [QrPayload], or null if the image
 * carries no readable merchant QR (caller shows the "couldn't be read" banner).
 */
internal suspend fun decodeQrFromUri(context: Context, uri: Uri): QrPayload? {
    val image = try {
        InputImage.fromFilePath(context, uri)
    } catch (_: Exception) {
        return null
    }
    val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )
    return suspendCancellableCoroutine { cont ->
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val payload = barcodes
                    .asSequence()
                    .mapNotNull { QrPayload.fromDecoded(it.rawValue) }
                    .firstOrNull()
                cont.resume(payload)
            }
            .addOnFailureListener { cont.resume(null) }
        cont.invokeOnCancellation { scanner.close() }
    }
}
