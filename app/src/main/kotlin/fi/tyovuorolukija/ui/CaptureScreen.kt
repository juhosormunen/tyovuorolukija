package fi.tyovuorolukija.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executor

private const val TAG = "CaptureScreen"

/**
 * Kameranäkymä. Kuva tallennetaan välimuistiin ja välitetään [onImage]:lle.
 *
 * Käyttäjälle näytetään myös galleriapainike — samoja valokuvia on helppo
 * testata uudestaan ilman että paperi on käsillä.
 */
@Composable
fun CaptureScreen(
    hasCameraPermission: Boolean,
    undoable: UndoableBatch?,
    onRequestCameraPermission: () -> Unit,
    onPickFromGallery: () -> Unit,
    onImage: (Uri) -> Unit,
    onUndo: () -> Unit,
    onOpenHistory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { ContextCompat.getMainExecutor(context) }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasCameraPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            bindCamera(ctx, view, lifecycleOwner, imageCapture, executor)
                        }
                    },
                )
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
                "Aseta koko tuloste kuvaan otsikkoriviä (\"suunnitelma\") myöten, " +
                    "suoraan ylhäältä ja tasaisessa valossa.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = { takePhoto(context, imageCapture, executor, onImage) },
                enabled = hasCameraPermission,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                Text("  Ota kuva")
            }
            OutlinedButton(onClick = onPickFromGallery, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                Text("  Valitse kuva galleriasta")
            }

            if (undoable != null || onOpenHistory != null) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            onOpenHistory?.let { open ->
                TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Insights, contentDescription = null)
                    Text("  Historia ja tilastot")
                }
            }
            // Virheen huomaa usein vasta kalenterista — siksi kumous on tarjolla
            // vielä senkin jälkeen kun tallennusnäkymästä on poistuttu.
            if (undoable != null) {
                TextButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                    Text("  Kumoa edellinen tallennus (${undoable.calendarName})")
                }
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
        }.onFailure { Log.e(TAG, "Kameran sidonta epäonnistui", it) }
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
