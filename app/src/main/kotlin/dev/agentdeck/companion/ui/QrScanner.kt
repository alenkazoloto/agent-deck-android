package dev.agentdeck.companion.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.agentdeck.companion.data.QrDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera half of QR pairing: a full-screen viewfinder that answers exactly once, with the
 * raw contents of the first code it reads or with **null for every other way out** — Cancel, the
 * system back gesture, or a camera this phone would not open.
 *
 * Null is what the pairing form reads as "cancelled": nothing typed is lost and no error is
 * shown. That is the whole contract, and it is the same one the retired `ScanContract` had.
 */
@Composable
fun QrScannerOverlay(
    prompt: String,
    onResult: (String?) -> Unit,
) {
    Dialog(
        onDismissRequest = { onResult(null) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var unavailable by remember { mutableStateOf(false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (!unavailable) {
                Viewfinder(
                    modifier = Modifier.fillMaxSize(),
                    onDecoded = { onResult(it) },
                    onUnavailable = { unavailable = true },
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding(),
            ) {
                Text(
                    if (unavailable) {
                        "This phone's camera did not open, so type the details below instead."
                    } else {
                        prompt
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { onResult(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (unavailable) "Back to the form" else "Cancel")
                }
            }
        }
    }
}

/**
 * `PreviewView` bound to the host lifecycle, with the frames also going to a QR analyzer.
 *
 * [onDecoded] fires **once**: an analyzer runs at frame rate and sees the same code in every
 * frame after the first one it reads.
 */
@Composable
private fun Viewfinder(
    modifier: Modifier,
    onDecoded: (String) -> Unit,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val decoded by rememberUpdatedState(onDecoded)
    val unavailable by rememberUpdatedState(onUnavailable)
    val previewView = remember {
        PreviewView(context).apply {
            // TextureView, not SurfaceView: this preview lives in a dialog window, where a
            // SurfaceView punches a hole through to nothing on a good number of devices.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, previewView) {
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var released = false
        future.addListener(
            {
                if (released) return@addListener
                val cameras = runCatching { future.get() }.getOrNull()
                if (cameras == null) {
                    unavailable()
                    return@addListener
                }
                provider = cameras
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(
                    analysisExecutor,
                    QrAnalyzer { text ->
                        if (delivered.compareAndSet(false, true)) main.execute { decoded(text) }
                    },
                )
                // A front-facing-only phone is still entitled to scan; the back camera is a
                // preference here, not a requirement.
                val lens = runCatching { cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                    .getOrDefault(true)
                    .let { if (it) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA }
                runCatching {
                    cameras.unbindAll()
                    cameras.bindToLifecycle(lifecycleOwner, lens, preview, analysis)
                }.onFailure { unavailable() }
            },
            main,
        )

        onDispose {
            released = true
            // Released on the scanned path and the cancelled path alike: a bound camera is a
            // hardware handle this process holds until something takes it back.
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.semantics { contentDescription = "Camera viewfinder for the pairing QR code" },
    )
}

/** Frames in, one decoded string out, on the analysis executor. */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val decoder = QrDecoder()

    override fun analyze(image: ImageProxy) {
        try {
            val luma = image.packedLuma() ?: return
            decoder.decode(luma, image.width, image.height)?.let(onDecoded)
        } finally {
            // An unclosed frame stalls the pipeline — the analyzer is simply never called again.
            image.close()
        }
    }
}

/**
 * The Y plane, tightly packed, or null when this frame is not 8-bit luminance.
 *
 * CameraX pads every row out to `rowStride`, which equals `width` only by luck; copying row by
 * row is what keeps the decoder's `width` meaning the same thing as the image's.
 */
private fun ImageProxy.packedLuma(): ByteArray? {
    val plane = planes.firstOrNull() ?: return null
    if (plane.pixelStride != 1) return null
    val rowStride = plane.rowStride
    val buffer = plane.buffer
    if (rowStride < width || buffer.capacity() < (height - 1) * rowStride + width) return null
    val out = ByteArray(width * height)
    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(out, row * width, width)
    }
    return out
}
