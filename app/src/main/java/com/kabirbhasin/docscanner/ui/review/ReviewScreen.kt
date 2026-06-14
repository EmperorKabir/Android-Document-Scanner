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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.kabirbhasin.docscanner.model.DocumentMeta
import com.kabirbhasin.docscanner.model.FilterType
import com.kabirbhasin.docscanner.ui.rememberFilteredPage
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
    onRename: (String) -> Unit,
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
    var exportMenu by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { exportMenu = true }) {
                        Icon(painterResource(R.drawable.ic_export), stringResource(R.string.action_export))
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_pdf)) },
                            onClick = {
                                exportMenu = false
                                scope.launch {
                                    val uri = Exporter.exportPdf(context, store, document)
                                    if (uri != null) Exporter.shareSingle(context, uri, "application/pdf")
                                }
                            },
                        )
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
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                    IconButton(onClick = { onRotate(current.id) }) {
                        Icon(painterResource(R.drawable.ic_rotate), stringResource(R.string.action_rotate))
                    }
                    IconButton(onClick = { onDeletePage(current.id) }) {
                        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                    }
                    Button(onClick = onAddPage) { Text(stringResource(R.string.action_add_page)) }
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
}

@Composable
private fun FilterRow(selected: FilterType, onSelect: (FilterType) -> Unit) {
    val options = listOf(
        FilterType.ORIGINAL to R.string.filter_original,
        FilterType.GREYSCALE to R.string.filter_greyscale,
        FilterType.BLACK_WHITE to R.string.filter_bw,
        FilterType.MAGIC to R.string.filter_magic,
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
