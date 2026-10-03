package ch.digitana.dienstplan.ui.team

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ch.digitana.dienstplan.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kamera zum Einlesen des Beitrittscodes als QR-Code. Die Bilder verlassen das Gerät nicht:
 * CameraX liefert sie an ZXing, das den Code lokal liest. Der gelesene Text wird danach genau
 * wie ein eingetippter Code geprüft (Präfix, Länge, Prüfsumme).
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                CameraPreview(onCode = onResult, modifier = Modifier.fillMaxSize())
                // Rahmen als Zielhilfe.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(240.dp)
                        .border(3.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(24.dp)),
                )
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.scan_hint),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close), tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reported = remember { AtomicBoolean(false) }
    // Wird der Scanner geschlossen, bevor die Kamera bereit ist, darf sie danach nicht mehr starten.
    val disposed = remember { AtomicBoolean(false) }
    val decoder = remember { QrDecoder() }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val view = PreviewView(viewContext).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener(
                {
                    if (disposed.get()) return@addListener
                    val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { image ->
                        val text = image.use { decoder.decode(it) }
                        if (text != null && reported.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(viewContext).execute { currentOnCode(text) }
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                },
                ContextCompat.getMainExecutor(viewContext),
            )
            view
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            // Erst die Kamera lösen (die Instanz steht nach dem ersten Start sofort bereit), dann
            // den Thread der Bildanalyse beenden.
            val future = ProcessCameraProvider.getInstance(context)
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            } else {
                future.addListener({ runCatching { future.get().unbindAll() } }, ContextCompat.getMainExecutor(context))
            }
            executor.shutdown()
        }
    }
}

/** Liest QR-Codes aus dem Helligkeitskanal eines Kamerabilds (YUV_420_888). Nicht threadsicher. */
private class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }

    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val stride = plane.rowStride
        if (stride < image.width || data.size < stride * (image.height - 1) + image.width) return null
        val source = PlanarYUVLuminanceSource(data, stride, image.height, 0, 0, image.width, image.height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text?.takeIf { it.length <= MAX_TEXT }
        } catch (e: ReaderException) {
            null
        } catch (e: RuntimeException) {
            // Ein seltsames Bild darf die App nicht beenden; das nächste Bild kommt gleich.
            null
        } finally {
            reader.reset()
        }
    }

    private companion object {
        /** Ein Beitrittscode hat 52 Zeichen; längere Inhalte sind sicher kein Code. */
        const val MAX_TEXT = 512
    }
}
