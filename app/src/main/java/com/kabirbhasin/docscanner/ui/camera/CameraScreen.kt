package com.kabirbhasin.docscanner.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kabirbhasin.docscanner.R
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.cv.Quad
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private data class DetectedFrame(val quad: Quad, val srcW: Int, val srcH: Int)

private const val AUTO_CAPTURE_FRAMES = 11

@Composable
fun CameraScreen(onCaptured: (File) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.camera_permission_rationale))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.grant_access))
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
    }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val capturing = remember { AtomicBoolean(false) }
    var detection by remember { mutableStateOf<DetectedFrame?>(null) }

    val capture = rememberUpdatedState {
        if (capturing.compareAndSet(false, true)) {
            val file = File(context.cacheDir, "cap_${System.nanoTime()}.jpg")
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        onCaptured(file)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        capturing.set(false)
                    }
                },
            )
        }
    }

    val stability = remember { Stability() }
    val onFrame = rememberUpdatedState<(DetectedFrame?) -> Unit> { frame ->
        detection = frame
        val quad = frame?.quad
        if (quad == null) {
            stability.last = null
            stability.count = 0
        } else {
            val prev = stability.last
            stability.count = if (prev != null && quadDelta(prev, quad) < 0.025f) stability.count + 1 else 1
            stability.last = quad
            if (stability.count >= AUTO_CAPTURE_FRAMES) {
                stability.count = 0
                capture.value.invoke()
            }
        }
    }
    val analyzer = remember {
        ImageAnalysis.Analyzer { image ->
            try {
                onFrame.value(analyseFrame(image))
            } catch (_: Throwable) {
                onFrame.value(null)
            } finally {
                image.close()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        QuadOverlay(detection, Modifier.fillMaxSize())

        TextButton(
            onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(8.dp),
        ) {
            Text(
                text = stringResource(
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                        ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                        else -> R.string.flash_off
                    },
                ),
                color = Color.White,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel), color = Color.White)
            }
            FilledIconButton(
                onClick = { capture.value.invoke() },
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
            ) {}
            Spacer(Modifier.size(72.dp))
        }
    }
}

private class Stability {
    var last: Quad? = null
    var count: Int = 0
}

@Composable
private fun QuadOverlay(detection: DetectedFrame?, modifier: Modifier) {
    Canvas(modifier) {
        val frame = detection ?: return@Canvas
        val cw = size.width
        val ch = size.height
        val scale = maxOf(cw / frame.srcW.toFloat(), ch / frame.srcH.toFloat())
        val dw = frame.srcW * scale
        val dh = frame.srcH * scale
        val ox = (cw - dw) / 2f
        val oy = (ch - dh) / 2f
        val pts = frame.quad.toList().map { Offset(ox + it.x * dw, oy + it.y * dh) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(path, Color(0x3300E5A0))
        for (i in pts.indices) {
            drawLine(Color(0xFF00E5A0), pts[i], pts[(i + 1) % pts.size], strokeWidth = 6f)
        }
        pts.forEach { drawCircle(Color(0xFF00E5A0), radius = 14f, center = it) }
    }
}

private fun quadDelta(a: Quad, b: Quad): Float {
    val pa = a.toList()
    val pb = b.toList()
    var sum = 0f
    for (i in 0 until 4) sum += abs(pa[i].x - pb[i].x) + abs(pa[i].y - pb[i].y)
    return sum / 8f
}

private fun analyseFrame(image: ImageProxy): DetectedFrame? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = image.width
    val h = image.height
    if (w < 8 || h < 8) return null

    val target = 220
    val step = (maxOf(w, h) + target - 1) / target
    val gw = w / step
    val gh = h / step
    if (gw < 4 || gh < 4) return null

    val gray = IntArray(gw * gh)
    for (gy in 0 until gh) {
        val rowBase = gy * step * rowStride
        for (gx in 0 until gw) {
            val index = rowBase + gx * step * pixelStride
            gray[gy * gw + gx] = buffer.get(index).toInt() and 0xFF
        }
    }

    val (upright, uw, uh) = rotate(gray, gw, gh, image.imageInfo.rotationDegrees)
    val quad = ImagePipeline.detectFromLuma(upright, uw, uh) ?: return null
    return DetectedFrame(quad, uw, uh)
}

private fun rotate(src: IntArray, w: Int, h: Int, degrees: Int): Triple<IntArray, Int, Int> = when (degrees) {
    90 -> {
        val dst = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) dst[x * h + (h - 1 - y)] = src[y * w + x]
        Triple(dst, h, w)
    }
    180 -> {
        val dst = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
        Triple(dst, w, h)
    }
    270 -> {
        val dst = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) dst[(w - 1 - x) * h + y] = src[y * w + x]
        Triple(dst, h, w)
    }
    else -> Triple(src, w, h)
}
