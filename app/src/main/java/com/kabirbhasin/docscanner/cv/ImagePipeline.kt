package com.kabirbhasin.docscanner.cv

import android.graphics.Bitmap
import com.kabirbhasin.docscanner.model.FilterType
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/** Classic computer-vision document pipeline: edge detection, perspective correction, enhancement. */
object ImagePipeline {

    private const val WORK_EDGE = 500.0

    /** Detect the largest convex quadrilateral (the page). Returns null if none is found. */
    fun detectPage(bitmap: Bitmap): Quad? {
        val src = Mat()
        Utils.bitmapToMat(bitmap.asArgb(), src)

        val longest = maxOf(src.width(), src.height()).toDouble()
        val scale = if (longest > WORK_EDGE) WORK_EDGE / longest else 1.0
        val work = Mat()
        Imgproc.resize(src, work, Size(src.width() * scale, src.height() * scale))

        val gray = Mat()
        Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Mat.ones(Size(3.0, 3.0), CvType.CV_8U))

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val frameArea = work.width().toDouble() * work.height().toDouble()
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < frameArea * 0.15) continue
            val curve = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(curve, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
            if (approx.total() == 4L &&
                area > bestArea &&
                Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))
            ) {
                bestArea = area
                best = approx.toArray()
            }
        }

        val width = src.width().toFloat()
        val height = src.height().toFloat()
        val quad = best?.let { pts ->
            val ordered = orderCorners(pts)
            Quad.of(
                ordered.map { p ->
                    Corner((p.x / scale / width).toFloat(), (p.y / scale / height).toFloat())
                },
            )
        }

        listOf(src, work, gray, edges).forEach(Mat::release)
        return quad
    }

    /** Warp the page region defined by [quad] into a flat, rectangular bitmap. */
    fun warp(bitmap: Bitmap, quad: Quad): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap.asArgb(), src)

        val w = bitmap.width.toDouble()
        val h = bitmap.height.toDouble()
        val tl = Point(quad.tl.x * w, quad.tl.y * h)
        val tr = Point(quad.tr.x * w, quad.tr.y * h)
        val br = Point(quad.br.x * w, quad.br.y * h)
        val bl = Point(quad.bl.x * w, quad.bl.y * h)

        val outW = maxOf(dist(tl, tr), dist(bl, br)).toInt().coerceAtLeast(1)
        val outH = maxOf(dist(tl, bl), dist(tr, br)).toInt().coerceAtLeast(1)

        val srcPts = MatOfPoint2f(tl, tr, br, bl)
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
        val out = Mat()
        Imgproc.warpPerspective(src, out, transform, Size(outW.toDouble(), outH.toDouble()))

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, result)
        listOf(src, out, transform).forEach(Mat::release)
        return result
    }

    /** Apply an enhancement filter, returning a new bitmap. */
    fun applyFilter(bitmap: Bitmap, filter: FilterType): Bitmap {
        if (filter == FilterType.ORIGINAL) return bitmap

        val src = Mat()
        Utils.bitmapToMat(bitmap.asArgb(), src)
        val out = Mat()
        when (filter) {
            FilterType.GREYSCALE -> {
                Imgproc.cvtColor(src, out, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.cvtColor(out, out, Imgproc.COLOR_GRAY2RGBA)
            }
            FilterType.BLACK_WHITE -> {
                val gray = Mat()
                Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.adaptiveThreshold(
                    gray, gray, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 15, 10.0,
                )
                Imgproc.cvtColor(gray, out, Imgproc.COLOR_GRAY2RGBA)
                gray.release()
            }
            FilterType.MAGIC -> magicColour(src, out)
            FilterType.ORIGINAL -> src.copyTo(out)
        }

        val result = Bitmap.createBitmap(out.width(), out.height(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, result)
        src.release()
        out.release()
        return result
    }

    private fun magicColour(src: Mat, out: Mat) {
        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)

        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)

        val rgb = Mat()
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        val blur = Mat()
        Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 3.0)
        Core.addWeighted(rgb, 1.5, blur, -0.5, 0.0, rgb)
        Imgproc.cvtColor(rgb, out, Imgproc.COLOR_RGB2RGBA)

        listOf(lab, rgb, blur).forEach(Mat::release)
        channels.forEach(Mat::release)
    }

    private fun orderCorners(p: Array<Point>): List<Point> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.minByOrNull { it.y - it.x }!!
        val bl = p.maxByOrNull { it.y - it.x }!!
        return listOf(tl, tr, br, bl)
    }

    private fun dist(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)

    private fun Bitmap.asArgb(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
}
