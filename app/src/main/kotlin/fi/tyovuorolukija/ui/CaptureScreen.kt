package fi.tyovuorolukija.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

private const val TAG = "CaptureScreen"

/**
 * Kameranäkymä.
 *
 * Kaksi asiaa, jotka ratkaisevat tunnistuksen onnistumisen hämärässä:
 *
 * 1. **Valo.** Työvuorolista kuvataan usein sisätiloissa. Jatkuva valo (torch)
 *    salaman sijaan, koska silloin heijastukset näkyvät jo esikatselussa —
 *    salaman kanssa ne selviävät vasta otetusta kuvasta.
 * 2. **Tarkennus tekstiin.** Jatkuva automaattitarkennus tarkentaa usein paperin
 *    reunaan tai taustaan. Napauttamalla tekstiä tarkennuksen saa sinne mihin
 *    sen pitää osua.
 */
@Composable
fun CaptureScreen(
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onPickFromGallery: () -> Unit,
    onImage: (Uri) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }

    // Tarkka tila laadulle: tuloste on pientä monospace-tekstiä, ja nopeus on
    // tässä täysin toissijaista tarkkuuteen nähden.
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val previewView = remember { PreviewView(context) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var hasTorch by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    var focusTick by remember { mutableStateOf(0) }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            bindCamera(context, previewView, lifecycleOwner, imageCapture, executor) { bound ->
                camera = bound
                hasTorch = bound?.cameraInfo?.hasFlashUnit() == true
            }
        }
    }

    // Valo pois kun näkymästä poistutaan — muuten se jäisi palamaan taskussa.
    DisposableEffect(Unit) {
        onDispose { runCatching { camera?.cameraControl?.enableTorch(false) } }
    }

    // Tarkennusympyrä häivytetään pehmeästi, jotta napautus tuntuu vastaavan.
    val focusAlpha by animateFloatAsState(
        targetValue = if (focusAt != null) 1f else 0f,
        label = "focusRing",
    )
    LaunchedEffect(focusTick) {
        if (focusAt != null) {
            kotlinx.coroutines.delay(1200)
            focusAt = null
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(camera) {
                            detectTapGestures { offset ->
                                val control = camera?.cameraControl ?: return@detectTapGestures
                                val point = previewView.meteringPointFactory
                                    .createPoint(offset.x, offset.y)
                                runCatching {
                                    control.startFocusAndMetering(
                                        FocusMeteringAction.Builder(
                                            point,
                                            FocusMeteringAction.FLAG_AF or
                                                FocusMeteringAction.FLAG_AE,
                                        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                                    )
                                }
                                focusAt = offset
                                focusTick++
                            }
                        },
                )

                focusAt?.let { point ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White.copy(alpha = focusAlpha),
                            radius = 44f,
                            center = point,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        "Kameralupa puuttuu. Voit myös valita valmiin kuvan galleriasta.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onRequestCameraPermission) { Text("Salli kamera") }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (hasCameraPermission) {
                    "Aseta koko tuloste kuvaan otsikkoriviä (\"suunnitelma\") myöten, " +
                        "suoraan ylhäältä. Napauta tekstiä tarkentaaksesi siihen."
                } else {
                    "Aseta koko tuloste kuvaan otsikkoriviä (\"suunnitelma\") myöten."
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { takePhoto(context, imageCapture, executor, onImage) },
                    enabled = hasCameraPermission,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Text("  Ota kuva")
                }
                if (hasCameraPermission && hasTorch) {
                    FilledTonalButton(
                        onClick = {
                            torchOn = !torchOn
                            runCatching { camera?.cameraControl?.enableTorch(torchOn) }
                        },
                    ) {
                        Icon(
                            if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (torchOn) "Sammuta valo" else "Sytytä valo",
                        )
                    }
                }
            }

            OutlinedButton(onClick = onPickFromGallery, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Text("  Valitse kuva galleriasta")
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Takaisin")
            }
        }
    }
}

private fun bindCamera(
    context: Context,
    view: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    imageCapture: ImageCapture,
    executor: Executor,
    onBound: (Camera?) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        runCatching {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        }.onFailure {
            Log.e(TAG, "Kameran sidonta epäonnistui", it)
            onBound(null)
        }.onSuccess(onBound)
    }, executor)
}

private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onImage: (Uri) -> Unit,
) {
    val file = File(context.cacheDir, "skannaus_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()

    imageCapture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            onImage(output.savedUri ?: Uri.fromFile(file))
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e(TAG, "Kuvan tallennus epäonnistui", exception)
        }
    })
}
