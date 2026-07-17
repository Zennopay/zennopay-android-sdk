package com.zennopay.sdk.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX [ImageAnalysis.Analyzer] that runs ML Kit on-device barcode detection
 * on each frame, filters to QR codes, applies the display-only [QrPayload] gate,
 * and fires [onQr] once with the first plausible EMVCo merchant QR.
 *
 * Single-fire: after the first hit we latch [handled] so subsequent frames are
 * ignored — the SDK submits the raw payload to the backend exactly once per
 * scan and the caller tears the analyzer down.
 */
internal class QrCodeAnalyzer(
    private val onQr: (QrPayload) -> Unit,
) : ImageAnalysis.Analyzer {

    private val handled = AtomicBoolean(false)

    /**
     * Re-arm the analyzer after a rejected submit: the screen returns to the
     * live scanner and the next plausible frame should fire [onQr] again.
     */
    fun rearm() {
        handled.set(false)
    }

    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (handled.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val payload = QrPayload.fromDecoded(barcode.rawValue)
                    if (payload != null && handled.compareAndSet(false, true)) {
                        onQr(payload)
                        break
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
