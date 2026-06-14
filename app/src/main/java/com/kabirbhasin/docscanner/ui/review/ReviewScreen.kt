package com.kabirbhasin.docscanner.ui.review

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kabirbhasin.docscanner.R
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.export.Exporter
import com.kabirbhasin.docscanner.export.PageSize
import com.kabirbhasin.docscanner.model.DocumentMeta
import com.kabirbhasin.docscanner.model.FilterType
import com.kabirbhasin.docscanner.model.PageMeta
import com.kabirbhasin.docscanner.ui.rememberFilteredPage
import com.kabirbhasin.docscanner.ui.rememberImageFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    documentId: String,
    store: DocumentStore,
    document: DocumentMeta?,
    onBack: () -> Unit,
    onAddPage: () -> Unit,
    onSetFilter: (String, FilterType) -> Unit,
    onDeletePage: (String) -> Unit,
    onMovePage: (String, Int) -> Unit,
    onRotate: (String) -> Unit,
    onSign: (String) -> Unit,
    onRetake: (String) -> Unit,
    onRename: (String) -> Unit,
    onSetWatermark: (String) -> Unit,
    onCombineIdCard: () -> Unit,
) {
    if (document == null || document.pages.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages = document.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var showRename by remember { mutableStateOf(false) }
    var showWatermark by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        document.title,
                        maxLines = 1,
                        modifier = Modifier.clickable { showRename = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    IconButton(onClick = { gridView = !gridView }) {
                        Icon(painterResource(R.drawable.ic_grid), stringResource(R.string.action_grid))
                    }
                    IconButton(onClick = { exportMenu = true }) {
                        Icon(painterResource(R.drawable.ic_export), stringResource(R.string.action_export))
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        val pdfItems = listOf(
                            R.string.export_pdf_a4 to PageSize.A4,
                            R.string.export_pdf_letter to PageSize.LETTER,
                            R.string.export_pdf_fit to PageSize.FIT,
                        )
                        pdfItems.forEach { (label, size) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    exportMenu = false
                                    scope.launch {
                                        val uri = Exporter.exportPdf(context, store, document, size)
                                        if (uri != null) Exporter.shareSingle(context, uri, "application/pdf")
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_images)) },
                            onClick = {
                                exportMenu = false
                                scope.launch {
                                    val uris = Exporter.exportImages(context, store, document)
                                    Exporter.shareMultiple(context, uris, "image/jpeg")
                                }
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_watermark)) },
                            onClick = {
                                exportMenu = false
                                showWatermark = true
                            },
                        )
                        if (pages.size >= 2) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_id_card)) },
                                onClick = {
                                    exportMenu = false
                                    onCombineIdCard()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (gridView) {
                PageGrid(
                    documentId = documentId,
                    store = store,
                    pages = pages,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onSelect = { index ->
                        gridView = false
                        scope.launch { pagerState.scrollToPage(index) }
                    },
                )
            } else {
                HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index ->
                val page = pages[index]
                val bmp by rememberFilteredPage(store, documentId, page)
                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                    val b = bmp
                    if (b != null) {
                        Image(
                            bitmap = b.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            val current = pages.getOrNull(pagerState.currentPage)
            if (current != null) {
                FilterRow(current.filter) { onSetFilter(current.id, it) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { onMovePage(current.id, -1) },
                        enabled = pagerState.currentPage > 0,
                    ) {
                        Icon(painterResource(R.drawable.ic_back), "Move left")
                    }
                    Text(
                        stringResource(R.string.page_position, pagerState.currentPage + 1, pages.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(
                        onClick = { onMovePage(current.id, 1) },
                        enabled = pagerState.currentPage < pages.size - 1,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_back),
                            "Move right",
                            modifier = Modifier.rotate(180f),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onAddPage) { Text(stringResource(R.string.action_add_page)) }
                    Box {
                        IconButton(onClick = { pageMenu = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = pageMenu, onDismissRequest = { pageMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rotate)) },
                                onClick = { pageMenu = false; onRotate(current.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_sign)) },
                                onClick = { pageMenu = false; onSign(current.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_retake)) },
                                onClick = { pageMenu = false; onRetake(current.id) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = { pageMenu = false; onDeletePage(current.id) },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    if (showRename) {
        RenameDialog(
            initial = document.title,
            onDismiss = { showRename = false },
            onConfirm = {
                onRename(it)
                showRename = false
            },
        )
    }

    if (showWatermark) {
        TextEntryDialog(
            title = stringResource(R.string.action_watermark),
            hint = stringResource(R.string.watermark_hint),
            initial = document.watermark ?: "",
            confirmLabel = stringResource(R.string.action_done),
            allowBlank = true,
            onDismiss = { showWatermark = false },
            onConfirm = {
                onSetWatermark(it)
                showWatermark = false
            },
        )
    }
}

@Composable
private fun TextEntryDialog(
    title: String,
    hint: String,
    initial: String,
    confirmLabel: String,
    allowBlank: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(hint) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = allowBlank || text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageGrid(
    documentId: String,
    store: DocumentStore,
    pages: List<PageMeta>,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages, key = { _, page -> page.id }) { index, page ->
            val thumb by rememberImageFile(store.thumbFile(documentId, page.id))
            Card(onClick = { onSelect(index) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(0.75f),
                    contentAlignment = Alignment.Center,
                ) {
                    thumb?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(selected: FilterType, onSelect: (FilterType) -> Unit) {
    val options = listOf(
        FilterType.ORIGINAL to R.string.filter_original,
        FilterType.MAGIC to R.string.filter_magic,
        FilterType.LIGHTEN to R.string.filter_lighten,
        FilterType.GREYSCALE to R.string.filter_greyscale,
        FilterType.BLACK_WHITE to R.string.filter_bw,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.rename_hint)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.action_rename)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
