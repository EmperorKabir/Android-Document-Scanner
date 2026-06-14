package com.kabirbhasin.docscanner.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.kabirbhasin.docscanner.cv.ImagePipeline
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.model.PageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberImageFile(file: File?): State<Bitmap?> =
    produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        value = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
        } else {
            null
        }
    }

@Composable
fun rememberFilteredPage(store: DocumentStore, documentId: String, page: PageMeta): State<Bitmap?> =
    produceState<Bitmap?>(null, documentId, page.id, page.filter, page.rev) {
        val original = withContext(Dispatchers.IO) { store.loadBitmap(store.pageFile(documentId, page.id)) }
        value = if (original == null) null
        else withContext(Dispatchers.Default) { ImagePipeline.applyFilter(original, page.filter) }
    }
