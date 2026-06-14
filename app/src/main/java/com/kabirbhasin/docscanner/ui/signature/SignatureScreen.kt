package com.kabirbhasin.docscanner.ui.signature

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kabirbhasin.docscanner.R

@Composable
fun SignatureScreen(onDone: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.White)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ ->
                            current = current + change.position
                            change.consume()
                        },
                        onDragEnd = {
                            strokes.add(current)
                            current = emptyList()
                        },
                    )
                },
        ) {
            (strokes + listOf(current)).forEach { stroke ->
                if (stroke.size > 1) {
                    val path = Path().apply {
                        moveTo(stroke[0].x, stroke[0].y)
                        for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
                    }
                    drawPath(
                        path,
                        Color.Black,
                        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            TextButton(onClick = {
                strokes.clear()
                current = emptyList()
            }) { Text(stringResource(R.string.action_clear)) }
            Button(onClick = {
                val all = strokes.filter { it.size > 1 }
                if (all.isNotEmpty() && size.width > 0 && size.height > 0) {
                    onDone(rasterize(all, size))
                }
            }) { Text(stringResource(R.string.action_done)) }
        }
    }
}

private fun rasterize(strokes: List<List<Offset>>, size: IntSize): Bitmap {
    val full = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(full)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = 6f
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }
    strokes.forEach { stroke ->
        val path = android.graphics.Path().apply {
            moveTo(stroke[0].x, stroke[0].y)
            for (i in 1 until stroke.size) lineTo(stroke[i].x, stroke[i].y)
        }
        canvas.drawPath(path, paint)
    }
    return trim(full)
}

private fun trim(bmp: Bitmap): Bitmap {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((px[y * w + x] ushr 24) != 0) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return bmp
    val pad = 8
    val left = (minX - pad).coerceAtLeast(0)
    val top = (minY - pad).coerceAtLeast(0)
    val right = (maxX + pad).coerceAtMost(w - 1)
    val bottom = (maxY + pad).coerceAtMost(h - 1)
    return Bitmap.createBitmap(bmp, left, top, right - left + 1, bottom - top + 1)
}
