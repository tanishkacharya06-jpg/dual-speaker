package com.example.aicamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setCameraContent() else setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { Text("Camera permission is required to use AI Camera.", Modifier.padding(24.dp)) } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setCameraContent()
        } else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun setCameraContent() {
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("Ready") }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("AI Camera", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).also { view ->
                                val providerFuture = ProcessCameraProvider.getInstance(context)
                                providerFuture.addListener({
                                    val provider = providerFuture.get()
                                    val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                    imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                                    try {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(this@MainActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                                    } catch (e: Exception) { status = "Camera could not start: ${e.message}" }
                                }, ContextCompat.getMainExecutor(context))
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    Text(status, modifier = Modifier.padding(8.dp))
                    Button(onClick = {
                        val capture = imageCapture
                        if (capture == null) { status = "Camera is starting"; return@Button }
                        status = "Capturing…"
                        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                image.close()
                                runOnUiThread { status = "Photo captured. Connect your enhancement server to process it." }
                            }
                            override fun onError(exception: ImageCaptureException) {
                                runOnUiThread { status = "Capture failed: ${exception.message}" }
                            }
                        })
                    }, modifier = Modifier.padding(20.dp)) { Text("Capture Photo") }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
    }
}
