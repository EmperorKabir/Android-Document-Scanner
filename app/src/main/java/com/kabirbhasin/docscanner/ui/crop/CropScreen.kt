package com.kabirbhasin.docscanner.ui.crop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kabirbhasin.docscanner.R
import com.kabirbhasin.docscanner.cv.Corner
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.cv.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CropScreen(rawImagePath: String, onConfirm: (Quad) -> Unit, onCancel: () -> Unit) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = rawImagePath) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(rawImagePath) }
    }
    var corners by remember { mutableStateOf<List<Offset>?>(null) }
    val density = LocalDensity.current

    LaunchedEffect(bitmap) {
        val bmp = bitmap ?: return@LaunchedEffect
        val quad = withContext(Dispatchers.Default) { ImagePipeline.detectPage(bmp) } ?: Quad.FULL
        corners = quad.toList().map { Offset(it.x, it.y) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            val pts = corners
            if (bmp == null || pts == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                val margin = with(density) { 40.dp.toPx() }
                val availW = (cw - 2 * margin).coerceAtLeast(1f)
                val availH = (ch - 2 * margin).coerceAtLeast(1f)
                val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                val dw: Float
                val dh: Float
                if (availW / availH > ratio) {
                    dh = availH
                    dw = availH * ratio
                } else {
                    dw = availW
                    dh = availW / ratio
                }
                val ox = (cw - dw) / 2f
                val oy = (ch - dh) / 2f
                val image = remember(bmp) { bmp.asImageBitmap() }

                Canvas(
                    Modifier.fillMaxSize().pointerInput(dw, dh, ox, oy) {
                        var dragIndex = -1
                        detectDragGestures(
                            onDragStart = { start ->
                                val current = corners ?: return@detectDragGestures
                                dragIndex = current.indices.minByOrNull { i ->
                                    val px = ox + current[i].x * dw
                                    val py = oy + current[i].y * dh
                                    (start.x - px) * (start.x - px) + (start.y - py) * (start.y - py)
                                } ?: -1
                            },
                            onDrag = { change, _ ->
                                if (dragIndex < 0) return@detectDragGestures
                                change.consume()
                                val nx = ((change.position.x - ox) / dw).coerceIn(0f, 1f)
                                val ny = ((change.position.y - oy) / dh).coerceIn(0f, 1f)
                                corners = corners?.toMutableList()?.also { it[dragIndex] = Offset(nx, ny) }
                            },
                        )
                    },
                ) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                    )
                    val screenPts = pts.map { Offset(ox + it.x * dw, oy + it.y * dh) }
                    val path = Path().apply {
                        moveTo(screenPts[0].x, screenPts[0].y)
                        lineTo(screenPts[1].x, screenPts[1].y)
                        lineTo(screenPts[2].x, screenPts[2].y)
                        lineTo(screenPts[3].x, screenPts[3].y)
                        close()
                    }
                    drawPath(path, Color(0x3300E5A0))
                    for (i in screenPts.indices) {
                        val a = screenPts[i]
                        val b = screenPts[(i + 1) % screenPts.size]
                        drawLine(Color(0xFF00E5A0), a, b, strokeWidth = 4f)
                    }
                    screenPts.forEach { drawCircle(Color(0xFF00E5A0), radius = 22f, center = it) }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_retake), color = Color.White)
            }
            Button(
                onClick = { corners?.let { c -> onConfirm(Quad.of(c.map { Corner(it.x, it.y) })) } },
                enabled = corners != null,
            ) {
                Text(stringResource(R.string.action_use))
            }
        }
    }
}
