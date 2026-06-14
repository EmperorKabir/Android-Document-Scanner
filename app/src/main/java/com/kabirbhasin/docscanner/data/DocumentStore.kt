package com.kabirbhasin.docscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kabirbhasin.docscanner.model.DocumentMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Persists documents as a JSON index plus page/thumbnail image files under the app's files dir. */
class DocumentStore private constructor(context: Context) {

    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()

    private val _documents = MutableStateFlow(readIndex())
    val documents: StateFlow<List<DocumentMeta>> = _documents.asStateFlow()

    fun document(id: String): DocumentMeta? = _documents.value.firstOrNull { it.id == id }

    fun documentDir(id: String): File = File(root, id).apply { mkdirs() }

    fun pageFile(documentId: String, pageId: String): File =
        File(documentDir(documentId), "$pageId.jpg")

    fun thumbFile(documentId: String, pageId: String): File =
        File(documentDir(documentId), "${pageId}_thumb.jpg")

    suspend fun savePageImage(documentId: String, pageId: String, bitmap: Bitmap) =
        withContext(Dispatchers.IO) {
            pageFile(documentId, pageId).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            val thumb = scaleTo(bitmap, 480)
            thumbFile(documentId, pageId).outputStream().use {
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
        }

    suspend fun rotatePage(documentId: String, pageId: String, degrees: Int) =
        withContext(Dispatchers.IO) {
            val source = loadBitmap(pageFile(documentId, pageId)) ?: return@withContext
            val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            pageFile(documentId, pageId).outputStream().use {
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            val thumb = scaleTo(rotated, 480)
            thumbFile(documentId, pageId).outputStream().use {
                thumb.compress(Bitmap.CompressFormat.JPEG, 80, it)
            }
            Unit
        }

    suspend fun deletePageImage(documentId: String, pageId: String) =
        withContext(Dispatchers.IO) {
            pageFile(documentId, pageId).delete()
            thumbFile(documentId, pageId).delete()
            Unit
        }

    fun loadBitmap(file: File): Bitmap? =
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null

    suspend fun upsert(document: DocumentMeta) {
        val current = _documents.value.toMutableList()
        val index = current.indexOfFirst { it.id == document.id }
        if (index >= 0) current[index] = document else current.add(0, document)
        persist(current.sortedByDescending { it.updatedAt })
    }

    suspend fun delete(documentId: String) {
        withContext(Dispatchers.IO) { documentDir(documentId).deleteRecursively() }
        persist(_documents.value.filterNot { it.id == documentId })
    }

    fun newDocumentId(): String = UUID.randomUUID().toString()

    fun newPageId(): String = UUID.randomUUID().toString()

    private suspend fun persist(list: List<DocumentMeta>) = writeLock.withLock {
        withContext(Dispatchers.IO) { indexFile.writeText(json.encodeToString(list)) }
        _documents.value = list
    }

    private fun readIndex(): List<DocumentMeta> = runCatching {
        if (indexFile.exists()) json.decodeFromString<List<DocumentMeta>>(indexFile.readText())
        else emptyList()
    }.getOrDefault(emptyList())

    private fun scaleTo(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        @Volatile
        private var instance: DocumentStore? = null

        fun get(context: Context): DocumentStore =
            instance ?: synchronized(this) {
                instance ?: DocumentStore(context.applicationContext).also { instance = it }
            }
    }
}
