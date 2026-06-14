package com.kabirbhasin.docscanner.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kabirbhasin.docscanner.R
import com.kabirbhasin.docscanner.data.DocumentStore
import com.kabirbhasin.docscanner.model.DocumentMeta
import com.kabirbhasin.docscanner.ui.adaptive.DevicePosture
import com.kabirbhasin.docscanner.ui.rememberImageFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    devicePosture: DevicePosture,
    documents: List<DocumentMeta>,
    store: DocumentStore,
    onScan: () -> Unit,
    onImport: (Uri) -> Unit,
    onImportPdf: (Uri) -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMerge: (String, String) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onImport(uri) }
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) onImportPdf(uri) }
    var importMenu by remember { mutableStateOf(false) }
    var mergeSource by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }

    val columns = when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> 2
        WindowWidthSizeClass.Medium -> 3
        else -> 4
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = {
                            selecting = false
                            selected.clear()
                        }) {
                            Icon(painterResource(R.drawable.ic_back), stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selected.size == documents.size) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(documents.map { it.id })
                            }
                        }) {
                            Icon(painterResource(R.drawable.ic_check), stringResource(R.string.action_select_all))
                        }
                        IconButton(
                            onClick = {
                                val ids = selected.toList()
                                selecting = false
                                selected.clear()
                                ids.forEach { onDelete(it) }
                            },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_title)) },
                    actions = {
                        Box {
                            IconButton(onClick = { importMenu = true }) {
                                Icon(painterResource(R.drawable.ic_import), stringResource(R.string.action_import))
                            }
                            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_images)) },
                                    onClick = {
                                        importMenu = false
                                        picker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_pdf)) },
                                    onClick = {
                                        importMenu = false
                                        pdfPicker.launch("application/pdf")
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(painterResource(R.drawable.ic_scan), null) },
                text = { Text(stringResource(R.string.action_scan)) },
            )
        },
    ) { padding ->
        if (documents.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentCard(
                        doc = doc,
                        store = store,
                        selecting = selecting,
                        isSelected = selected.contains(doc.id),
                        onOpen = onOpen,
                        onDelete = onDelete,
                        onMergeRequest = { mergeSource = it },
                        onToggleSelect = {
                            if (selected.contains(doc.id)) selected.remove(doc.id) else selected.add(doc.id)
                        },
                        onEnterSelect = {
                            selecting = true
                            if (!selected.contains(doc.id)) selected.add(doc.id)
                        },
                    )
                }
            }
        }
    }

    mergeSource?.let { sourceId ->
        val targets = documents.filter { it.id != sourceId }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text(stringResource(R.string.merge_dialog_title)) },
            text = {
                Column {
                    if (targets.isEmpty()) {
                        Text(stringResource(R.string.merge_none))
                    } else {
                        targets.forEach { target ->
                            TextButton(onClick = {
                                onMerge(sourceId, target.id)
                                mergeSource = null
                            }) {
                                Text(target.title, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { mergeSource = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    doc: DocumentMeta,
    store: DocumentStore,
    selecting: Boolean,
    isSelected: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMergeRequest: (String) -> Unit,
    onToggleSelect: () -> Unit,
    onEnterSelect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val thumbFile = doc.pages.firstOrNull()?.let { store.thumbFile(doc.id, it.id) }
    val thumb by rememberImageFile(thumbFile)

    Card(
        modifier = Modifier.combinedClickable(
            onClick = { if (selecting) onToggleSelect() else onOpen(doc.id) },
            onLongClick = { if (!selecting) menu = true },
        ),
        border = if (isSelected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = doc.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                doc.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pageCountText(doc.pages.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_select)) },
                onClick = {
                    menu = false
                    onEnterSelect()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_merge)) },
                onClick = {
                    menu = false
                    onMergeRequest(doc.id)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    menu = false
                    onDelete(doc.id)
                },
            )
        }
    }
}

@Composable
private fun pageCountText(count: Int): String =
    if (count == 1) stringResource(R.string.pages_one) else stringResource(R.string.pages_other, count)
