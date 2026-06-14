package com.kabirbhasin.docscanner.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PdfImporter {

    /** Render each page of [uri] to a bitmap at roughly 144 dpi. */
    suspend fun render(context: Context, uri: Uri): List<Bitmap> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "import_${System.nanoTime()}.pdf")
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
            true
        } ?: false
        if (!copied) return@withContext emptyList()

        val result = ArrayList<Bitmap>()
        try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val scale = 2f
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            result.add(bitmap)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Unreadable / encrypted PDF — return whatever rendered.
        } finally {
            temp.delete()
        }
        result
    }
}
