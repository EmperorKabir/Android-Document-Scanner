package com.kabirbhasin.docscanner.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.model.DocumentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

enum class PageSize { FIT, A4, LETTER }

object Exporter {

    suspend fun exportPdf(context: Context, store: DocumentStore, doc: DocumentMeta, size: PageSize): Uri? =
        withContext(Dispatchers.IO) {
            if (doc.pages.isEmpty()) return@withContext null
            val pdf = PdfDocument()
            doc.pages.forEachIndexed { index, page ->
                val original = store.loadBitmap(store.pageFile(doc.id, page.id)) ?: return@forEachIndexed
                val bitmap = watermarked(ImagePipeline.applyFilter(original, page.filter), doc.watermark)
                val (pw, ph) = pageDimensions(size, bitmap)
                val info = PdfDocument.PageInfo.Builder(pw, ph, index + 1).create()
                val pdfPage = pdf.startPage(info)
                val margin = if (size == PageSize.FIT) 0f else 24f
                val scale = min((pw - 2 * margin) / bitmap.width, (ph - 2 * margin) / bitmap.height)
                val dw = bitmap.width * scale
                val dh = bitmap.height * scale
                val left = (pw - dw) / 2f
                val top = (ph - dh) / 2f
                pdfPage.canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, top, left + dw, top + dh),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                pdf.finishPage(pdfPage)
            }
            val file = File(sharedDir(context), "${safeName(doc.title)}.pdf")
            file.outputStream().use { pdf.writeTo(it) }
            pdf.close()
            uriFor(context, file)
        }

    suspend fun exportImages(context: Context, store: DocumentStore, doc: DocumentMeta): ArrayList<Uri> =
        withContext(Dispatchers.IO) {
            val uris = ArrayList<Uri>()
            doc.pages.forEachIndexed { index, page ->
                val original = store.loadBitmap(store.pageFile(doc.id, page.id)) ?: return@forEachIndexed
                val bitmap = watermarked(ImagePipeline.applyFilter(original, page.filter), doc.watermark)
                val file = File(sharedDir(context), "${safeName(doc.title)}_${index + 1}.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                uris.add(uriFor(context, file))
            }
            uris
        }

    fun shareSingle(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    fun shareMultiple(context: Context, uris: ArrayList<Uri>, mime: String) {
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun pageDimensions(size: PageSize, bitmap: Bitmap): Pair<Int, Int> {
        if (size == PageSize.FIT) return bitmap.width to bitmap.height
        val (portraitW, portraitH) = if (size == PageSize.A4) 595 to 842 else 612 to 792
        return if (bitmap.width > bitmap.height) portraitH to portraitW else portraitW to portraitH
    }

    private fun watermarked(src: Bitmap, text: String?): Bitmap {
        if (text.isNullOrBlank()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22000000
            textSize = out.width / 16f
            isFakeBoldText = true
        }
        canvas.save()
        canvas.rotate(-30f, out.width / 2f, out.height / 2f)
        val stepY = paint.textSize * 5f
        val stepX = paint.measureText(text) + paint.textSize * 3f
        var y = -out.height.toFloat()
        while (y < out.height * 2f) {
            var x = -out.width.toFloat()
            while (x < out.width * 2f) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
        }
        canvas.restore()
        return out
    }

    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "document" }
}
