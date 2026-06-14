package com.kabirbhasin.docscanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kabirbhasin.docscanner.ui.adaptive.DevicePosture
import com.kabirbhasin.docscanner.ui.camera.CameraScreen
import com.kabirbhasin.docscanner.ui.crop.CropScreen
import com.kabirbhasin.docscanner.ui.home.HomeScreen
import com.kabirbhasin.docscanner.ui.review.ReviewScreen

@Composable
fun DocScannerApp(
    windowSizeClass: WindowSizeClass,
    devicePosture: DevicePosture,
    viewModel: AppViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()

    BackHandler(enabled = viewModel.screen != Screen.Home) {
        when (val s = viewModel.screen) {
            is Screen.Camera -> if (s.isNewDocument) viewModel.goHome() else viewModel.openDocument(s.documentId)
            is Screen.Crop -> viewModel.cancelCrop(s.documentId, s.rawPath)
            is Screen.Review -> viewModel.goHome()
            Screen.Home -> Unit
        }
    }

    when (val s = viewModel.screen) {
        Screen.Home -> HomeScreen(
            windowSizeClass = windowSizeClass,
            devicePosture = devicePosture,
            documents = documents,
            store = viewModel.store,
            onScan = viewModel::startNewScan,
            onImport = viewModel::importImage,
            onOpen = viewModel::openDocument,
            onDelete = viewModel::deleteDocument,
        )

        is Screen.Camera -> {
            val workingDoc = documents.firstOrNull { it.id == s.documentId }
            val lastPage = workingDoc?.pages?.lastOrNull()
            CameraScreen(
                pageCount = workingDoc?.pages?.size ?: 0,
                lastThumb = lastPage?.let { viewModel.store.thumbFile(s.documentId, it.id) },
                onCaptured = { file, batch ->
                    if (batch) viewModel.addBatchPage(s.documentId, file)
                    else viewModel.onCaptured(s.documentId, s.isNewDocument, file)
                },
                onDone = {
                    if ((workingDoc?.pages?.size ?: 0) > 0) viewModel.openDocument(s.documentId)
                    else viewModel.goHome()
                },
                onCancel = { if (s.isNewDocument) viewModel.goHome() else viewModel.openDocument(s.documentId) },
            )
        }

        is Screen.Crop -> CropScreen(
            rawImagePath = s.rawPath,
            onConfirm = { quad -> viewModel.onCropConfirmed(s.documentId, s.pageId, s.rawPath, quad) },
            onCancel = { viewModel.cancelCrop(s.documentId, s.rawPath) },
        )

        is Screen.Review -> ReviewScreen(
            documentId = s.documentId,
            store = viewModel.store,
            document = documents.firstOrNull { it.id == s.documentId },
            onBack = viewModel::goHome,
            onAddPage = { viewModel.addPage(s.documentId) },
            onSetFilter = { pageId, filter -> viewModel.setPageFilter(s.documentId, pageId, filter) },
            onDeletePage = { pageId -> viewModel.deletePage(s.documentId, pageId) },
            onMovePage = { pageId, delta -> viewModel.movePage(s.documentId, pageId, delta) },
            onRotate = { pageId -> viewModel.rotatePage(s.documentId, pageId) },
            onRename = { title -> viewModel.renameDocument(s.documentId, title) },
            onSetWatermark = { text -> viewModel.setWatermark(s.documentId, text) },
        )
    }
}
