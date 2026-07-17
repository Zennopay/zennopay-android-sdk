package com.zennopay.sdk.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zennopay.sdk.internal.CheckoutEvent
import com.zennopay.sdk.internal.CheckoutState
import com.zennopay.sdk.internal.CorridorBranding
import com.zennopay.sdk.scanner.QrCodeAnalyzer
import com.zennopay.sdk.scanner.QrPayload
import com.zennopay.sdk.scanner.decodeQrFromUri
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Full-screen camera scanner per the partner-approved reference (iOS
 * `ScannerScreen` mirror): chrome-on-black camera surface, grey corner-bracket
 * reticle with an animated accent scan-line, corridor-aware "Look for these
 * logos before scanning" chips, and a bottom control row (gallery / Paste code
 * / torch) plus an "I need help scanning" link.
 */
@Composable
internal fun ScannerScreen(
    scanning: CheckoutState.Scanning?,
    checking: Boolean,
    corridor: String?,
    onEvent: (CheckoutEvent) -> Unit,
) {
    val palette = ZColors.palette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val branding = CorridorBranding.entry(corridor)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var galleryHint by remember { mutableStateOf<String?>(null) }
    var showPasteSheet by remember { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            permissionDenied = true
            onEvent(CheckoutEvent.CameraPermissionDenied)
        }
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val payload = decodeQrFromUri(context, uri)
                if (payload != null) {
                    galleryHint = null
                    onEvent(CheckoutEvent.QrCaptured(payload.raw))
                } else {
                    galleryHint = "No QR code found in that image. Try another."
                }
            }
        }
    }

    val cameraUsable = hasCameraPermission && (scanning?.cameraAvailable != false)

    // The scanner is always chrome-on-black — a camera surface.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraUsable) {
            ScannerViewport(
                torchOn = torchOn,
                rearmKey = scanning,
                onTorchAvailable = { torchAvailable = it },
                onQr = { onEvent(CheckoutEvent.QrCaptured(it.raw)) },
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            ScannerTopBar(
                cameraUsable = cameraUsable,
                permissionDenied = permissionDenied,
                onClose = { onEvent(CheckoutEvent.Cancel) },
            )
            Spacer(Modifier.weight(1f))
            ScannerReticle(
                accent = palette.accent,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(horizontal = ZSpace.xl),
            )
            Spacer(Modifier.weight(1f))
            BrandingSection(
                branding = branding,
                hint = galleryHint
                    ?: scanning?.transientError?.let { humanMessage(it) },
            )
            ScannerControls(
                torchOn = torchOn,
                torchAvailable = torchAvailable && cameraUsable,
                onGallery = {
                    galleryHint = null
                    galleryLauncher.launch("image/*")
                },
                onPaste = { showPasteSheet = true },
                onTorch = { torchOn = !torchOn },
            )
            Text(
                text = "I need help scanning",
                color = Color.White,
                style = tabularStyle(16.sp, ZType.medium, Color.White),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = ZSpace.xs, bottom = ZSpace.sm)
                    .height(44.dp)
                    .clickable { showHelpSheet = true }
                    .padding(horizontal = ZSpace.md, vertical = ZSpace.sm)
                    .testTag("zp.scan.help"),
            )
        }

        if (checking) {
            CheckingPill(modifier = Modifier.align(Alignment.Center))
        }
    }

    if (showPasteSheet) {
        PasteCodeSheet(
            onDismiss = { showPasteSheet = false },
            onSubmit = { raw ->
                showPasteSheet = false
                onEvent(CheckoutEvent.QrCaptured(raw))
            },
        )
    }
    if (showHelpSheet) {
        ScanHelpSheet(branding = branding, onDismiss = { showHelpSheet = false })
    }
}

/**
 * CameraX preview + ML Kit analysis filling the screen. Re-arms the single-fire
 * analyzer whenever the flow returns to the scanner ([rearmKey] changes, e.g.
 * after a rejected submit). Reports torch availability from the bound camera.
 */
@Composable
private fun ScannerViewport(
    torchOn: Boolean,
    rearmKey: Any?,
    onTorchAvailable: (Boolean) -> Unit,
    onQr: (QrPayload) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { QrCodeAnalyzer(onQr) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }
    LaunchedEffect(rearmKey) {
        if (rearmKey != null) analyzer.rearm()
    }
    LaunchedEffect(torchOn, camera) {
        val cam = camera ?: return@LaunchedEffect
        onTorchAvailable(cam.cameraInfo.hasFlashUnit())
        if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(torchOn)
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }
                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // Bind failure: the chrome (paste + gallery) still works
                    // over black; no camera frames arrive.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Close X top-left + the no-camera status line. */
@Composable
private fun ScannerTopBar(
    cameraUsable: Boolean,
    permissionDenied: Boolean,
    onClose: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = ZSpace.sm)) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp)
                .testTag("zp.scan.close"),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close",
                tint = Color.White,
            )
        }
        if (!cameraUsable) {
            Text(
                text = if (permissionDenied) {
                    "Camera access is off — use Paste code, or allow camera in Settings."
                } else {
                    "Camera unavailable — use Paste code or your gallery."
                },
                color = Color.White.copy(alpha = 0.75f),
                style = tabularStyle(12.sp, ZType.regular, Color.White.copy(alpha = 0.75f)),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = ZSpace.xxl),
            )
        }
    }
}

/** "Checking…" pill shown over the viewport while `/scan` is in flight. */
@Composable
private fun CheckingPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = ZSpace.lg, vertical = ZSpace.md),
        horizontalArrangement = Arrangement.spacedBy(ZSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Checking…",
            color = Color.White,
            style = tabularStyle(15.sp, ZType.medium, Color.White),
        )
    }
}

/**
 * Grey corner-bracket reticle with the animated accent scan-line: a bright
 * line with a translucent gradient trail sweeping top→bottom on repeat.
 */
@Composable
internal fun ScannerReticle(accent: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanSweep")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanSweepFraction",
    )

    Canvas(modifier = modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val len = minOf(w, h) * 0.12f
        val r = 14.dp.toPx()
        val strokeWidth = 3.dp.toPx()

        // Four rounded corner brackets (iOS `CornerBrackets` mirror).
        val path = Path().apply {
            // Top-left
            moveTo(0f, r + len)
            lineTo(0f, r)
            quadraticBezierTo(0f, 0f, r, 0f)
            lineTo(r + len, 0f)
            // Top-right
            moveTo(w - r - len, 0f)
            lineTo(w - r, 0f)
            quadraticBezierTo(w, 0f, w, r)
            lineTo(w, r + len)
            // Bottom-right
            moveTo(w, h - r - len)
            lineTo(w, h - r)
            quadraticBezierTo(w, h, w - r, h)
            lineTo(w - r - len, h)
            // Bottom-left
            moveTo(r + len, h)
            lineTo(r, h)
            quadraticBezierTo(0f, h, 0f, h - r)
            lineTo(0f, h - r - len)
        }
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.65f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        // Scan line + translucent trail, inset from the brackets.
        val inset = 10.dp.toPx()
        val lineH = 3.dp.toPx()
        val trailH = 54.dp.toPx()
        val y = 4.dp.toPx() + sweep * (h - trailH - 8.dp.toPx())
        drawRect(
            color = accent,
            topLeft = Offset(inset, y),
            size = androidx.compose.ui.geometry.Size(w - inset * 2, lineH),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
                startY = y + lineH,
                endY = y + lineH + trailH,
            ),
            topLeft = Offset(inset, y + lineH),
            size = androidx.compose.ui.geometry.Size(w - inset * 2, trailH),
        )
    }
}

/**
 * The corridor branding row under the reticle: an optional transient hint,
 * then "Look for these logos before scanning" + the scheme chips rendered from
 * [CorridorBranding] data (styled text — no trademark bitmap assets).
 */
@Composable
private fun BrandingSection(branding: CorridorBranding.Entry?, hint: String?) {
    val shapes = LocalZShapes.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = ZSpace.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZSpace.md),
    ) {
        hint?.let {
            Text(
                text = it,
                color = Color.White,
                style = tabularStyle(13.sp, ZType.regular, Color.White),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = ZSpace.lg)
                    .background(
                        Color.Black.copy(alpha = 0.55f),
                        RoundedCornerShape(shapes.card),
                    )
                    .padding(horizontal = ZSpace.sm, vertical = ZSpace.xs)
                    .testTag("zp.scan.hint"),
            )
        }
        if (branding != null) {
            Text(
                text = "Look for these logos before scanning",
                color = Color.White,
                style = tabularStyle(15.sp, ZType.medium, Color.White),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                branding.chips.forEach { chip -> SchemeChipView(chip) }
            }
        }
    }
}

/**
 * One scheme chip: a white (or brand-colored) rounded square with a styled
 * wordmark approximation. NOT a trademark bitmap.
 */
@Composable
internal fun SchemeChipView(chip: CorridorBranding.SchemeChip) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(Color(chip.backgroundRgb), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (chip.stacked) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-2).dp),
            ) {
                chip.segments.forEach { seg -> SegmentText(seg) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                chip.segments.forEach { seg -> SegmentText(seg) }
            }
        }
    }
}

@Composable
private fun SegmentText(seg: CorridorBranding.Segment) {
    Text(
        text = seg.text,
        style = tabularStyle(12.sp, ZType.bold, Color(seg.rgb)),
        maxLines = 1,
    )
}

/**
 * Bottom controls: gallery circle button, the wide "Paste code" pill, and the
 * torch toggle circle (disabled when no flash unit is available).
 */
@Composable
private fun ScannerControls(
    torchOn: Boolean,
    torchAvailable: Boolean,
    onGallery: () -> Unit,
    onPaste: () -> Unit,
    onTorch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ZSpace.lg),
        horizontalArrangement = Arrangement.spacedBy(ZSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleControl(
            glyph = "🖼",
            label = "Choose a QR from your photos",
            testTag = "zp.scan.gallery",
            onClick = onGallery,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(onClick = onPaste)
                .testTag("zp.scan.pasteCode"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Paste code",
                color = Color.White,
                style = tabularStyle(17.sp, ZType.medium, Color.White),
            )
        }
        CircleControl(
            glyph = if (torchOn) "⚡" else "⚡",
            label = if (torchOn) "Turn flashlight off" else "Turn flashlight on",
            testTag = "zp.scan.torch",
            enabled = torchAvailable,
            dimmedWhenOff = !torchOn,
            onClick = onTorch,
        )
    }
}

/** 56dp translucent-white circle control with an emoji glyph. */
@Composable
private fun CircleControl(
    glyph: String,
    label: String,
    testTag: String,
    enabled: Boolean = true,
    dimmedWhenOff: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = tabularStyle(18.sp, ZType.medium, Color.White),
            modifier = Modifier.alpha(if (dimmedWhenOff) 0.8f else 1f),
        )
    }
}

/**
 * The paste-QR bottom sheet: a multiline field, a one-tap "Paste from
 * clipboard" helper, and Continue. Test tags mirror the iOS accessibility
 * identifiers: `zp.scan.pasteField`, `zp.scan.pasteButton`, `zp.scan.continue`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasteCodeSheet(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val palette = ZColors.palette()
    val shapes = LocalZShapes.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var pasteText by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZSpace.md)
                .padding(bottom = ZSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ZSpace.md),
        ) {
            Text(
                text = "Paste code",
                color = palette.text,
                style = tabularStyle(20.sp, ZType.medium, palette.text),
            )
            Text(
                text = "Paste the QR code text to continue.",
                color = palette.text2,
                style = tabularStyle(14.sp, ZType.regular, palette.text2),
            )
            androidx.compose.material3.OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                minLines = 4,
                maxLines = 6,
                textStyle = tabularStyle(14.sp, ZType.regular, palette.text),
                shape = RoundedCornerShape(shapes.input),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.surface,
                    unfocusedContainerColor = palette.surface,
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.border,
                    cursorColor = palette.accent,
                    focusedTextColor = palette.text,
                    unfocusedTextColor = palette.text,
                ),
                modifier = Modifier.fillMaxWidth().testTag("zp.scan.pasteField"),
            )
            Text(
                text = "Paste from clipboard",
                color = palette.accent,
                style = tabularStyle(14.sp, ZType.medium, palette.accent),
                modifier = Modifier
                    .clickable {
                        clipboard.getText()?.text?.let { pasteText = it }
                    }
                    .padding(vertical = ZSpace.sm)
                    .testTag("zp.scan.pasteButton"),
            )
            ZPrimaryButton(
                label = "Continue",
                enabled = pasteText.isNotBlank(),
                testTag = "zp.scan.continue",
            ) {
                onSubmit(pasteText.trim())
            }
        }
    }
}

/**
 * "I need help scanning" sheet: which QRs the corridor supports + the
 * alternate capture paths.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanHelpSheet(
    branding: CorridorBranding.Entry?,
    onDismiss: () -> Unit,
) {
    val palette = ZColors.palette()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.bg) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZSpace.md)
                .padding(bottom = ZSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ZSpace.md),
        ) {
            Text(
                text = "Scanning help",
                color = palette.text,
                style = tabularStyle(20.sp, ZType.medium, palette.text),
            )
            HelpRow(
                glyph = "▣",
                text = if (branding != null) {
                    "This payment goes to ${branding.countryName}. ${branding.supportedQrHelp}"
                } else {
                    "Point the camera at the merchant's payment QR. We'll show you the " +
                        "amount before anything is charged."
                },
            )
            HelpRow(
                glyph = "🖼",
                text = "Have a screenshot? Tap the photo button to pick the QR image " +
                    "from your gallery.",
            )
            HelpRow(
                glyph = "📋",
                text = "Have the code as text? Tap Paste code and paste it in.",
            )
            HelpRow(
                glyph = "🔒",
                text = "You always review the amount and merchant before paying — " +
                    "nothing is charged while scanning.",
            )
        }
    }
}

@Composable
private fun HelpRow(glyph: String, text: String) {
    val palette = ZColors.palette()
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZSpace.md),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = glyph,
            color = palette.accent,
            style = tabularStyle(18.sp, ZType.regular, palette.accent),
        )
        Text(
            text = text,
            color = palette.text2,
            style = tabularStyle(14.sp, ZType.regular, palette.text2),
        )
    }
}
