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
 * Document image pipeline built entirely on the Android graphics framework: page detection
 * (still and live), perspective correction and enhancement. No native dependencies.
 */
object ImagePipeline {

    private const val DETECT_EDGE = 480

    /** Detect the page in a still bitmap; falls back to a small inset when nothing is found. */
    fun detectPage(bitmap: Bitmap): Quad {
        val scale = DETECT_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = luma(c)
        }
        return detectQuad(gray, w, h)?.quad ?: insetQuad()
    }

    /** Detect the page from a pre-extracted luminance buffer (live camera frames). Null if none. */
    fun detectFromLuma(gray: IntArray, w: Int, h: Int): Quad? = detectQuad(gray, w, h)?.quad

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
        FilterType.MAGIC -> magicScan(bitmap, 0.97f)
        FilterType.LIGHTEN -> magicScan(bitmap, 1.08f)
        FilterType.BLACK_WHITE -> adaptiveThreshold(bitmap)
    }

    // ---- Detection internals --------------------------------------------------

    private class Detection(val quad: Quad, val areaFraction: Float)

    private fun detectQuad(gray: IntArray, w: Int, h: Int): Detection? {
        val blurred = boxBlur(gray, w, h, (maxOf(w, h) / 120).coerceIn(2, 8))
        val t = otsu(blurred)
        val candidates = ArrayList<Detection>()
        largestMaskQuad(BooleanArray(w * h) { blurred[it] > t }, w, h)?.let { candidates.add(it) }
        largestMaskQuad(BooleanArray(w * h) { blurred[it] <= t }, w, h)?.let { candidates.add(it) }
        edgeEnclosedQuad(blurred, w, h)?.let { candidates.add(it) }
        return candidates.maxByOrNull { it.areaFraction }?.takeIf { it.areaFraction >= 0.18f }
    }

    /**
     * Detect the page as the region enclosed by the strongest edges: flood from the image border
     * through non-edge pixels, and whatever remains enclosed is the page. Robust when the page
     * barely contrasts with the surface (where intensity segmentation fails).
     */
    private fun edgeEnclosedQuad(gray: IntArray, w: Int, h: Int): Detection? {
        val n = w * h
        val grad = IntArray(n)
        var sum = 0L
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val a = gray[(y - 1) * w + x - 1]; val b = gray[(y - 1) * w + x]; val c = gray[(y - 1) * w + x + 1]
                val d = gray[y * w + x - 1]; val e = gray[y * w + x + 1]
                val f = gray[(y + 1) * w + x - 1]; val g = gray[(y + 1) * w + x]; val i = gray[(y + 1) * w + x + 1]
                val gx = -a - 2 * d - f + c + 2 * e + i
                val gy = -a - 2 * b - c + f + 2 * g + i
                val m = abs(gx) + abs(gy)
                grad[y * w + x] = m
                sum += m
            }
        }
        val threshold = (sum.toDouble() / n * 1.8).toInt().coerceAtLeast(24)
        val edge = dilate(BooleanArray(n) { grad[it] > threshold }, w, h)

        val outside = BooleanArray(n)
        val seedStack = IntArray(n)
        var ssp = 0
        for (x in 0 until w) {
            val top = x; val bottom = (h - 1) * w + x
            if (!edge[top] && !outside[top]) { outside[top] = true; seedStack[ssp++] = top }
            if (!edge[bottom] && !outside[bottom]) { outside[bottom] = true; seedStack[ssp++] = bottom }
        }
        for (y in 0 until h) {
            val left = y * w; val right = y * w + w - 1
            if (!edge[left] && !outside[left]) { outside[left] = true; seedStack[ssp++] = left }
            if (!edge[right] && !outside[right]) { outside[right] = true; seedStack[ssp++] = right }
        }
        while (ssp > 0) {
            val p = seedStack[--ssp]
            val x = p % w; val y = p / w
            if (x > 0) { val q = p - 1; if (!edge[q] && !outside[q]) { outside[q] = true; seedStack[ssp++] = q } }
            if (x < w - 1) { val q = p + 1; if (!edge[q] && !outside[q]) { outside[q] = true; seedStack[ssp++] = q } }
            if (y > 0) { val q = p - w; if (!edge[q] && !outside[q]) { outside[q] = true; seedStack[ssp++] = q } }
            if (y < h - 1) { val q = p + w; if (!edge[q] && !outside[q]) { outside[q] = true; seedStack[ssp++] = q } }
        }
        return largestMaskQuad(BooleanArray(n) { !edge[it] && !outside[it] }, w, h)
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!src[y * w + x]) continue
                val y0 = (y - 1).coerceAtLeast(0); val y1 = (y + 1).coerceAtMost(h - 1)
                val x0 = (x - 1).coerceAtLeast(0); val x1 = (x + 1).coerceAtMost(w - 1)
                for (ny in y0..y1) for (nx in x0..x1) out[ny * w + nx] = true
            }
        }
        return out
    }

    private fun boxBlur(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius < 1) return gray
        val stride = w + 1
        val integral = LongArray(stride * (h + 1))
        for (y in 1..h) {
            var row = 0L
            for (x in 1..w) {
                row += gray[(y - 1) * w + (x - 1)]
                integral[y * stride + x] = integral[(y - 1) * stride + x] + row
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y1 = (y - radius).coerceAtLeast(0); val y2 = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                val x1 = (x - radius).coerceAtLeast(0); val x2 = (x + radius).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1).toLong() * (y2 - y1 + 1)
                val s = integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]
                out[y * w + x] = (s / area).toInt()
            }
        }
        return out
    }

    /**
     * Largest connected foreground region of plausible page size and high solidity, described by
     * its four extreme corners. Solidity rejects the hollow background frame (whose extreme corners
     * are the image corners) in favour of the solid, convex page region.
     */
    private fun largestMaskQuad(fg: BooleanArray, w: Int, h: Int): Detection? {
        val n = w * h
        val visited = BooleanArray(n)
        val stack = IntArray(n)

        var bestArea = 0
        var bestCorners: IntArray? = null

        for (origin in 0 until n) {
            if (!fg[origin] || visited[origin]) continue
            var sp = 0
            stack[sp++] = origin
            visited[origin] = true
            var area = 0
            var minSum = Int.MAX_VALUE
            var maxSum = Int.MIN_VALUE
            var minDiff = Int.MAX_VALUE
            var maxDiff = Int.MIN_VALUE
            var tl = origin
            var br = origin
            var tr = origin
            var bl = origin
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                val sum = x + y
                val diff = x - y
                if (sum < minSum) { minSum = sum; tl = p }
                if (sum > maxSum) { maxSum = sum; br = p }
                if (diff > maxDiff) { maxDiff = diff; tr = p }
                if (diff < minDiff) { minDiff = diff; bl = p }
                if (x > 0) { val q = p - 1; if (fg[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (x < w - 1) { val q = p + 1; if (fg[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (y > 0) { val q = p - w; if (fg[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
                if (y < h - 1) { val q = p + w; if (fg[q] && !visited[q]) { visited[q] = true; stack[sp++] = q } }
            }
            val frac = area.toFloat() / n
            if (frac in 0.18f..0.95f && area > bestArea) {
                val quadArea = quadAreaPx(tl, tr, br, bl, w)
                if (quadArea > 0f && area / quadArea >= 0.75f) {
                    bestArea = area
                    bestCorners = intArrayOf(tl, tr, br, bl)
                }
            }
        }

        val corners = bestCorners ?: return null
        val quad = Quad.of(corners.map { Corner((it % w) / w.toFloat(), (it / w) / h.toFloat()) })
        return Detection(quad, bestArea.toFloat() / n)
    }

    private fun quadAreaPx(tl: Int, tr: Int, br: Int, bl: Int, w: Int): Float {
        val xs = floatArrayOf((tl % w).toFloat(), (tr % w).toFloat(), (br % w).toFloat(), (bl % w).toFloat())
        val ys = floatArrayOf((tl / w).toFloat(), (tr / w).toFloat(), (br / w).toFloat(), (bl / w).toFloat())
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(a) / 2f
    }

    private fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g.coerceIn(0, 255)]++
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * hist[i]
        var sumB = 0.0
        var weightB = 0
        var maxVar = 0.0
        var threshold = 127
        for (i in 0..255) {
            weightB += hist[i]
            if (weightB == 0) continue
            val weightF = total - weightB
            if (weightF == 0) break
            sumB += i.toDouble() * hist[i]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val between = weightB.toDouble() * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > maxVar) {
                maxVar = between
                threshold = i
            }
        }
        return threshold
    }

    private fun insetQuad(): Quad {
        val m = 0.06f
        return Quad(Corner(m, m), Corner(1f - m, m), Corner(1f - m, 1f - m), Corner(m, 1f - m))
    }

    // ---- Filter internals -----------------------------------------------------

    private fun withColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Flatbed-clean colour scan: estimate the page illumination as a heavily downscaled background,
     * then divide each pixel by it. This whitens the paper, removes shadows and uneven lighting, and
     * preserves ink — the look of a sheet scanned on a flatbed.
     */
    private fun magicScan(src: Bitmap, gainFactor: Float): Bitmap {
        val w = src.width
        val h = src.height
        val sw = (w / 10).coerceAtLeast(1)
        val sh = (h / 10).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val bg = IntArray(sw * sh)
        small.getPixels(bg, 0, sw, 0, 0, sw, sh)

        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val gain = 255f * gainFactor
        for (y in 0 until h) {
            val sy = (y * sh / h).coerceIn(0, sh - 1)
            for (x in 0 until w) {
                val sx = (x * sw / w).coerceIn(0, sw - 1)
                val b = bg[sy * sw + sx]
                val p = px[y * w + x]
                val br = ((b shr 16) and 0xFF).coerceAtLeast(1)
                val bgC = ((b shr 8) and 0xFF).coerceAtLeast(1)
                val bb = (b and 0xFF).coerceAtLeast(1)
                val r = (((p shr 16) and 0xFF) * gain / br).toInt().coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) * gain / bgC).toInt().coerceIn(0, 255)
                val bl = ((p and 0xFF) * gain / bb).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Local adaptive threshold using an integral image, producing a clean bi-level scan. */
    private fun adaptiveThreshold(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h)
        for (i in pixels.indices) {
            gray[i] = luma(pixels[i])
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
                val s = integral[(y2 + 1) * stride + (x2 + 1)] -
                    integral[y1 * stride + (x2 + 1)] -
                    integral[(y2 + 1) * stride + x1] +
                    integral[y1 * stride + x1]
                val mean = s.toDouble() / area
                val v = if (gray[y * w + x] > mean * cFactor) 0xFF else 0x00
                out[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    private fun luma(c: Int): Int =
        (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()

    private fun dist(p: FloatArray, a: Int, b: Int): Float = hypot(p[a] - p[b], p[a + 1] - p[b + 1])
}
