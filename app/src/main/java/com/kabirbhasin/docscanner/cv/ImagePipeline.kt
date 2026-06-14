package com.kabirbhasin.docscanner.cv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.kabirbhasin.docscanner.model.FilterType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Document image pipeline built entirely on the Android graphics framework: page detection,
 * perspective correction and enhancement filters. No native dependencies.
 */
object ImagePipeline {

    private const val DETECT_EDGE = 420

    /**
     * Estimate the page rectangle from luminance-gradient projections. Falls back to a small
     * inset when the borders are not clearly separable; the user can always adjust the corners.
     */
    fun detectPage(bitmap: Bitmap): Quad {
        val scale = DETECT_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        val lum = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            lum[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }

        val colEnergy = FloatArray(w)
        val rowEnergy = FloatArray(h)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                colEnergy[x] += abs(lum[row + x + 1] - lum[row + x - 1])
                rowEnergy[y] += abs(lum[row + w + x] - lum[row - w + x])
            }
        }

        val left = strongestEdge(colEnergy, 0, (w * 0.45f).toInt())
        val right = strongestEdge(colEnergy, (w * 0.55f).toInt(), w)
        val top = strongestEdge(rowEnergy, 0, (h * 0.45f).toInt())
        val bottom = strongestEdge(rowEnergy, (h * 0.55f).toInt(), h)

        val separable = left != null && right != null && top != null && bottom != null &&
            right!! - left!! > w * 0.3f && bottom!! - top!! > h * 0.3f
        if (!separable) return insetQuad()

        val lx = left!! / w.toFloat()
        val rx = right!! / w.toFloat()
        val ty = top!! / h.toFloat()
        val by = bottom!! / h.toFloat()
        return Quad(Corner(lx, ty), Corner(rx, ty), Corner(rx, by), Corner(lx, by))
    }

    /** Perspective-correct the [quad] region into a flat rectangle. */
    fun warp(bitmap: Bitmap, quad: Quad): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val src = floatArrayOf(
            quad.tl.x * w, quad.tl.y * h,
            quad.tr.x * w, quad.tr.y * h,
            quad.br.x * w, quad.br.y * h,
            quad.bl.x * w, quad.bl.y * h,
        )
        val outW = maxOf(dist(src, 0, 2), dist(src, 6, 4)).roundToInt().coerceAtLeast(1)
        val outH = maxOf(dist(src, 0, 6), dist(src, 2, 4)).roundToInt().coerceAtLeast(1)
        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat(),
        )

        val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap = when (filter) {
        FilterType.ORIGINAL -> bitmap
        FilterType.GREYSCALE -> withColorMatrix(bitmap, ColorMatrix().apply { setSaturation(0f) })
        FilterType.MAGIC -> withColorMatrix(bitmap, magicMatrix())
        FilterType.BLACK_WHITE -> adaptiveThreshold(bitmap)
    }

    private fun strongestEdge(energy: FloatArray, from: Int, to: Int): Int? {
        val end = to.coerceAtMost(energy.size)
        if (from >= end) return null
        val mean = energy.average().toFloat()
        var best = -1
        var bestValue = 0f
        for (i in from until end) {
            if (energy[i] > bestValue && energy[i] > mean * 1.5f) {
                bestValue = energy[i]
                best = i
            }
        }
        return if (best >= 0) best else null
    }

    private fun insetQuad(): Quad {
        val m = 0.06f
        return Quad(Corner(m, m), Corner(1f - m, m), Corner(1f - m, 1f - m), Corner(m, 1f - m))
    }

    private fun withColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun magicMatrix(): ColorMatrix {
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        matrix.postConcat(ColorMatrix().apply { setSaturation(1.15f) })
        return matrix
    }

    /** Local adaptive threshold using an integral image, producing a clean bi-level scan. */
    private fun adaptiveThreshold(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
        }

        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 1..h) {
            var rowSum = 0L
            for (x in 1..w) {
                rowSum += gray[(y - 1) * w + (x - 1)]
                integral[y * stride + x] = integral[(y - 1) * stride + x] + rowSum
            }
        }

        val radius = (maxOf(w, h) / 32).coerceIn(8, 40)
        val cFactor = 0.85
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - radius).coerceAtLeast(0)
            val y2 = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - radius).coerceAtLeast(0)
                val x2 = (x + radius).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1).toLong() * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]
                val mean = sum.toDouble() / area
                val v = if (gray[y * w + x] > mean * cFactor) 0xFF else 0x00
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun dist(p: FloatArray, a: Int, b: Int): Float = hypot(p[a] - p[b], p[a + 1] - p[b + 1])
}
