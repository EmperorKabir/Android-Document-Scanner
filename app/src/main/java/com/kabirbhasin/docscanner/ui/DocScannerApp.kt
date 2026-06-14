package com.kabirbhasin.docscanner.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.kabirbhasin.docscanner.ui.adaptive.DevicePosture
import com.kabirbhasin.docscanner.ui.camera.CameraScreen
import com.kabirbhasin.docscanner.ui.crop.CropScreen
import com.kabirbhasin.docscanner.ui.home.HomeScreen
import com.kabirbhasin.docscanner.ui.review.ReviewScreen
import com.kabirbhasin.docscanner.ui.signature.SignatureScreen

@Composable
fun DocScannerApp(
    windowSizeClass: WindowSizeClass,
    devicePosture: DevicePosture,
    viewModel: AppViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            viewModel.onScanResult(scanResult?.pages?.map { it.imageUri } ?: emptyList())
        }
    }
    val launchScanner: () -> Unit = launch@{
        val activity = context.findActivity() ?: return@launch
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
    }

    BackHandler(enabled = viewModel.screen != Screen.Home) {
        when (val s = viewModel.screen) {
            is Screen.Camera -> if (s.isNewDocument) viewModel.goHome() else viewModel.openDocument(s.documentId)
            is Screen.Crop -> viewModel.cancelCrop(s.documentId, s.rawPath)
            is Screen.Review -> viewModel.goHome()
            is Screen.Signature -> viewModel.openDocument(s.documentId)
            Screen.Home -> Unit
        }
    }

    when (val s = viewModel.screen) {
        Screen.Home -> HomeScreen(
            windowSizeClass = windowSizeClass,
            devicePosture = devicePosture,
            documents = documents,
            store = viewModel.store,
            onScan = {
                viewModel.prepareNewScan()
                launchScanner()
            },
            onImport = viewModel::importImage,
            onImportPdf = viewModel::importPdf,
            onOpen = viewModel::openDocument,
            onDelete = viewModel::deleteDocument,
            onMerge = viewModel::mergeInto,
        )

        is Screen.Camera -> {
            val workingDoc = documents.firstOrNull { it.id == s.documentId }
            val lastPage = workingDoc?.pages?.lastOrNull()
            CameraScreen(
                pageCount = workingDoc?.pages?.size ?: 0,
                lastThumb = lastPage?.let { viewModel.store.thumbFile(s.documentId, it.id) },
                onCaptured = { file, batch ->
                    if (batch) viewModel.addBatchPage(s.documentId, file)
                    else viewModel.onCaptured(s.documentId, s.isNewDocument, s.replacePageId, file)
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
            onConfirm = { quad, rotation ->
                viewModel.onCropConfirmed(s.documentId, s.pageId, s.rawPath, quad, rotation)
            },
            onCancel = { viewModel.cancelCrop(s.documentId, s.rawPath) },
        )

        is Screen.Review -> ReviewScreen(
            documentId = s.documentId,
            store = viewModel.store,
            document = documents.firstOrNull { it.id == s.documentId },
            onBack = viewModel::goHome,
            onAddPage = {
                viewModel.prepareAddPage(s.documentId)
                launchScanner()
            },
            onSetFilter = { pageId, filter -> viewModel.setPageFilter(s.documentId, pageId, filter) },
            onDeletePage = { pageId -> viewModel.deletePage(s.documentId, pageId) },
            onMovePage = { pageId, delta -> viewModel.movePage(s.documentId, pageId, delta) },
            onRotate = { pageId -> viewModel.rotatePage(s.documentId, pageId) },
            onSign = { pageId -> viewModel.startSignature(s.documentId, pageId) },
            onRetake = { pageId ->
                viewModel.prepareRetake(s.documentId, pageId)
                launchScanner()
            },
            onRename = { title -> viewModel.renameDocument(s.documentId, title) },
            onSetWatermark = { text -> viewModel.setWatermark(s.documentId, text) },
            onCombineIdCard = { viewModel.combineIdCard(s.documentId) },
        )

        is Screen.Signature -> SignatureScreen(
            onDone = { bitmap -> viewModel.applySignature(s.documentId, s.pageId, bitmap) },
            onCancel = { viewModel.openDocument(s.documentId) },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
