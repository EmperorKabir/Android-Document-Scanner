package com.kabirbhasin.docscanner.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.cv.Quad
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.model.DocumentMeta
import com.kabirbhasin.docscanner.model.FilterType
import com.kabirbhasin.docscanner.model.PageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val store = DocumentStore.get(app)
    val documents = store.documents

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set

    fun goHome() {
        screen = Screen.Home
    }

    fun startNewScan() {
        screen = Screen.Camera(store.newDocumentId(), isNewDocument = true)
    }

    fun addPage(documentId: String) {
        screen = Screen.Camera(documentId, isNewDocument = false)
    }

    fun openDocument(documentId: String) {
        screen = Screen.Review(documentId)
    }

    fun onCaptured(documentId: String, isNewDocument: Boolean, replacePageId: String?, captured: File) {
        val pageId = replacePageId ?: store.newPageId()
        val raw = rawFile(pageId)
        captured.copyTo(raw, overwrite = true)
        captured.delete()
        screen = Screen.Crop(documentId, pageId, isNewDocument, raw.absolutePath)
    }

    fun startRetake(documentId: String, pageId: String) {
        screen = Screen.Camera(documentId, isNewDocument = false, replacePageId = pageId)
    }

    fun addBatchPage(documentId: String, captured: File) {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) { ImagePipeline.decodeOriented(captured.absolutePath) }
            withContext(Dispatchers.IO) { captured.delete() }
            if (raw == null) return@launch
            val pageId = store.newPageId()
            val quad = withContext(Dispatchers.Default) { ImagePipeline.detectPage(raw) }
            val warped = withContext(Dispatchers.Default) { ImagePipeline.warp(raw, quad) }
            store.savePageImage(documentId, pageId, warped)
            val now = System.currentTimeMillis()
            val existing = store.document(documentId)
            val base = existing ?: DocumentMeta(documentId, defaultTitle(now), now, now)
            store.upsert(base.copy(pages = base.pages + PageMeta(pageId, FilterType.MAGIC), updatedAt = now))
        }
    }

    fun importPdf(uri: Uri) {
        viewModelScope.launch {
            val bitmaps = com.kabirbhasin.docscanner.export.PdfImporter.render(getApplication(), uri)
            if (bitmaps.isEmpty()) return@launch
            val docId = store.newDocumentId()
            val now = System.currentTimeMillis()
            val pages = bitmaps.map { bitmap ->
                val pageId = store.newPageId()
                store.savePageImage(docId, pageId, bitmap)
                PageMeta(pageId, FilterType.ORIGINAL)
            }
            store.upsert(DocumentMeta(docId, defaultTitle(now), now, now, pages))
            screen = Screen.Review(docId)
        }
    }

    fun importImage(uri: Uri) {
        viewModelScope.launch {
            val pageId = store.newPageId()
            val raw = rawFile(pageId)
            val copied = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    raw.outputStream().use { input.copyTo(it) }
                    true
                } ?: false
            }
            if (copied) {
                screen = Screen.Crop(store.newDocumentId(), pageId, isNewDocument = true, raw.absolutePath)
            }
        }
    }

    fun onCropConfirmed(documentId: String, pageId: String, rawPath: String, quad: Quad, rotation: Int) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) { ImagePipeline.decodeOriented(rawPath) }
            if (decoded == null) {
                goReviewOrHome(documentId)
                return@launch
            }
            val raw = if (rotation != 0) {
                withContext(Dispatchers.Default) { ImagePipeline.rotate(decoded, rotation) }
            } else {
                decoded
            }
            val warped = withContext(Dispatchers.Default) { ImagePipeline.warp(raw, quad) }
            store.savePageImage(documentId, pageId, warped)
            withContext(Dispatchers.IO) { File(rawPath).delete() }

            val now = System.currentTimeMillis()
            val existing = store.document(documentId)
            val isReplace = existing?.pages?.any { it.id == pageId } == true
            val pages = if (isReplace) {
                existing!!.pages.map { if (it.id == pageId) it.copy(rev = it.rev + 1) else it }
            } else {
                (existing?.pages ?: emptyList()) + PageMeta(pageId, FilterType.MAGIC)
            }
            val base = existing ?: DocumentMeta(documentId, defaultTitle(now), now, now)
            store.upsert(base.copy(pages = pages, updatedAt = now))
            screen = Screen.Review(documentId)
        }
    }

    fun cancelCrop(documentId: String, rawPath: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { File(rawPath).delete() } }
        goReviewOrHome(documentId)
    }

    fun setPageFilter(documentId: String, pageId: String, filter: FilterType) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            val pages = doc.pages.map { if (it.id == pageId) it.copy(filter = filter) else it }
            store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        }
    }

    fun startSignature(documentId: String, pageId: String) {
        screen = Screen.Signature(documentId, pageId)
    }

    fun applySignature(documentId: String, pageId: String, signature: Bitmap) {
        viewModelScope.launch {
            store.stampSignature(documentId, pageId, signature)
            val doc = store.document(documentId) ?: return@launch
            val pages = doc.pages.map { if (it.id == pageId) it.copy(rev = it.rev + 1) else it }
            store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
            screen = Screen.Review(documentId)
        }
    }

    fun rotatePage(documentId: String, pageId: String) {
        viewModelScope.launch {
            store.rotatePage(documentId, pageId, 90)
            val doc = store.document(documentId) ?: return@launch
            val pages = doc.pages.map { if (it.id == pageId) it.copy(rev = it.rev + 1) else it }
            store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deletePage(documentId: String, pageId: String) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            store.deletePageImage(documentId, pageId)
            val pages = doc.pages.filterNot { it.id == pageId }
            if (pages.isEmpty()) {
                store.delete(documentId)
                screen = Screen.Home
            } else {
                store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun movePage(documentId: String, pageId: String, delta: Int) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            val pages = doc.pages.toMutableList()
            val from = pages.indexOfFirst { it.id == pageId }
            val to = from + delta
            if (from in pages.indices && to in pages.indices) {
                pages.add(to, pages.removeAt(from))
                store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun renameDocument(documentId: String, title: String) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            store.upsert(doc.copy(title = title.trim().ifBlank { doc.title }, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch { store.delete(documentId) }
    }

    fun combineIdCard(documentId: String) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            if (doc.pages.size < 2) return@launch
            val front = store.loadBitmap(store.pageFile(documentId, doc.pages[0].id)) ?: return@launch
            val back = store.loadBitmap(store.pageFile(documentId, doc.pages[1].id)) ?: return@launch
            val combined = withContext(Dispatchers.Default) { ImagePipeline.combineCards(front, back) }
            val newPageId = store.newPageId()
            store.savePageImage(documentId, newPageId, combined)
            store.deletePageImage(documentId, doc.pages[0].id)
            store.deletePageImage(documentId, doc.pages[1].id)
            val pages = listOf(PageMeta(newPageId, FilterType.ORIGINAL)) + doc.pages.drop(2)
            store.upsert(doc.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        }
    }

    fun mergeInto(sourceId: String, targetId: String) {
        if (sourceId == targetId) return
        viewModelScope.launch {
            val source = store.document(sourceId) ?: return@launch
            val target = store.document(targetId) ?: return@launch
            val newPages = source.pages.mapNotNull { page ->
                val bitmap = store.loadBitmap(store.pageFile(sourceId, page.id)) ?: return@mapNotNull null
                val pageId = store.newPageId()
                store.savePageImage(targetId, pageId, bitmap)
                PageMeta(pageId, page.filter)
            }
            store.upsert(target.copy(pages = target.pages + newPages, updatedAt = System.currentTimeMillis()))
            store.delete(sourceId)
        }
    }

    fun setWatermark(documentId: String, text: String) {
        viewModelScope.launch {
            val doc = store.document(documentId) ?: return@launch
            store.upsert(doc.copy(watermark = text.trim().ifBlank { null }, updatedAt = System.currentTimeMillis()))
        }
    }

    private fun goReviewOrHome(documentId: String) {
        screen = if (store.document(documentId) != null) Screen.Review(documentId) else Screen.Home
    }

    private fun rawFile(pageId: String) = File(getApplication<Application>().cacheDir, "raw_$pageId.jpg")

    private fun defaultTitle(now: Long): String =
        "Scan " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(now))
}
