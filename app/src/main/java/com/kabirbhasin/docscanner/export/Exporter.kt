package com.kabirbhasin.docscanner.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.model.DocumentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Exporter {

    suspend fun exportPdf(context: Context, store: DocumentStore, doc: DocumentMeta): Uri? =
        withContext(Dispatchers.IO) {
            if (doc.pages.isEmpty()) return@withContext null
            val pdf = PdfDocument()
            doc.pages.forEachIndexed { index, page ->
                val original = store.loadBitmap(store.pageFile(doc.id, page.id)) ?: return@forEachIndexed
                val bitmap = ImagePipeline.applyFilter(original, page.filter)
                val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val pdfPage = pdf.startPage(info)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
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
                val bitmap = ImagePipeline.applyFilter(original, page.filter)
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

    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().ifBlank { "document" }
}
