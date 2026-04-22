package com.orien.shadowing.presentation.materiallist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.domain.usecase.ImportTaskKind
import com.orien.shadowing.domain.usecase.ImportTaskSnapshot
import com.orien.shadowing.domain.usecase.ImportTaskStatus
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialListScreen(
    onNavigateToSentences: (Long) -> Unit,
    viewModel: MaterialListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var renameTarget by remember { mutableStateOf<MaterialEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var errorTask by remember { mutableStateOf<ImportTaskSnapshot?>(null) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(viewModel::importFromDirectoryUri)
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::importFromMediaUri)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MaterialListEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is MaterialListEvent.NavigateToSentences -> onNavigateToSentences(event.materialId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Shadowing Trainer") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showImportSheet) {
                Icon(Icons.Default.Add, contentDescription = "Import")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            state.materials.isEmpty() && state.importTasks.isEmpty() -> {
                EmptyMaterialsView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onImportClick = viewModel::showImportSheet
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.importTasks.isNotEmpty()) {
                        item("import_header") {
                            SectionTitle("Import Queue")
                        }
                        items(
                            items = state.importTasks,
                            key = { task -> task.id }
                        ) { task ->
                            ImportTaskCard(
                                task = task,
                                onClick = {
                                    if (task.status == ImportTaskStatus.FAILED) {
                                        errorTask = task
                                    }
                                }
                            )
                        }
                    }

                    if (state.materials.isNotEmpty()) {
                        if (state.importTasks.isNotEmpty()) {
                            item("materials_header") {
                                Spacer(modifier = Modifier.height(8.dp))
                                SectionTitle("Materials")
                            }
                        }
                        items(
                            items = state.materials,
                            key = { material -> material.id }
                        ) { material ->
                            MaterialCard(
                                material = material,
                                onClick = { viewModel.onMaterialClick(material.id) },
                                onRename = {
                                    renameTarget = material
                                    renameInput = material.title
                                },
                                onDelete = { viewModel.deleteMaterial(material.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename material") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("Material name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameMaterial(target.id, renameInput)
                        renameTarget = null
                    },
                    enabled = renameInput.trim().isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    errorTask?.let { task ->
        AlertDialog(
            onDismissRequest = { errorTask = null },
            title = { Text(task.title) },
            text = {
                Text(
                    text = task.errorMessage ?: "Unknown error.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { errorTask = null }) {
                    Text("Close")
                }
            }
        )
    }

    if (state.showImportSheet) {
        ImportBottomSheet(
            onDismiss = viewModel::hideImportSheet,
            onPickMedia = {
                viewModel.hideImportSheet()
                mediaPickerLauncher.launch(arrayOf("audio/*", "video/*"))
            },
            onPickDirectory = {
                viewModel.hideImportSheet()
                dirPickerLauncher.launch(null)
            },
            onImportDemo = viewModel::importDemo
        )
    }
}

@Composable
private fun EmptyMaterialsView(
    modifier: Modifier = Modifier,
    onImportClick: () -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.AutoMirrored.Filled.LibraryBooks,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No materials yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Import a media file or material package to start practicing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(onClick = onImportClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import material")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ImportTaskCard(
    task: ImportTaskSnapshot,
    onClick: () -> Unit
) {
    val statusColor = when (task.status) {
        ImportTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        ImportTaskStatus.QUEUED -> MaterialTheme.colorScheme.tertiary
        ImportTaskStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val detailText = when (task.status) {
        ImportTaskStatus.RUNNING -> {
            val progressPercent = ((task.progress ?: 0f) * 100).roundToInt().coerceIn(0, 100)
            "$progressPercent% - ${task.message}"
        }

        ImportTaskStatus.QUEUED -> task.message
        ImportTaskStatus.FAILED -> task.errorMessage ?: task.message
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = when (task.kind) {
                    ImportTaskKind.AUDIO -> Icons.Default.Headphones
                    ImportTaskKind.VIDEO -> Icons.Default.Videocam
                    ImportTaskKind.FOLDER -> Icons.Default.FolderOpen
                    ImportTaskKind.DEMO -> Icons.Default.AutoAwesome
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetadataPill(
                        text = taskKindLabel(task.kind),
                        color = MaterialTheme.colorScheme.primary
                    )
                    MetadataPill(
                        text = queueLabel(task),
                        color = statusColor
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (task.status == ImportTaskStatus.RUNNING) {
                    LinearProgressIndicator(
                        progress = { task.progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (task.status == ImportTaskStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MetadataPill(
    text: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun taskKindLabel(kind: ImportTaskKind): String = when (kind) {
    ImportTaskKind.AUDIO -> "AUDIO"
    ImportTaskKind.VIDEO -> "VIDEO"
    ImportTaskKind.FOLDER -> "FOLDER"
    ImportTaskKind.DEMO -> "DEMO"
}

private fun queueLabel(task: ImportTaskSnapshot): String {
    val position = task.queuePosition
    val total = task.activeTaskCount
    return when (task.status) {
        ImportTaskStatus.RUNNING -> {
            if (position != null && total != null) {
                "Queue $position/$total"
            } else {
                "Processing"
            }
        }

        ImportTaskStatus.QUEUED -> {
            if (position != null && total != null) {
                "Queued $position/$total"
            } else {
                "Queued"
            }
        }

        ImportTaskStatus.FAILED -> "Failed"
    }
}

@Composable
private fun MaterialCard(
    material: MaterialEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var showMenu by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (material.type == "video") {
                    Icons.Default.Videocam
                } else {
                    Icons.Default.Headphones
                },
                contentDescription = material.type,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = material.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = material.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = material.language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dateFormat.format(Date(material.createdAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            showMenu = false
                            onRename()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportBottomSheet(
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickDirectory: () -> Unit,
    onImportDemo: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Import material",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Choose where to import from.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            ImportOption(
                icon = Icons.Default.Headphones,
                title = "Import media file",
                description = "Pick an audio or video file and auto-generate the material package.",
                onClick = onPickMedia
            )

            Spacer(modifier = Modifier.height(12.dp))

            ImportOption(
                icon = Icons.Default.FolderOpen,
                title = "Import folder",
                description = "Pick a pre-processed package containing meta.json and sentences.json.",
                onClick = onPickDirectory
            )

            Spacer(modifier = Modifier.height(12.dp))

            ImportOption(
                icon = Icons.Default.AutoAwesome,
                title = "Import demo set",
                description = "Generate three sample lessons for smoke testing.",
                onClick = onImportDemo
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Package format",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Raw media import will run on-device speech analysis first, then generate this package structure internally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = buildString {
                            appendLine("material-package/")
                            appendLine("|- meta.json          # title, type, language")
                            appendLine("|- sentences.json     # sentence list with timestamps")
                            appendLine("|- audio.mp3          # optional full audio")
                            appendLine("|- clips/             # optional per-sentence clips")
                            appendLine("   |- 001.mp3")
                            appendLine("   |- 002.mp3")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "sentences.json example",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = """[{"index":0,"textOriginal":"Hello","textZh":"Greeting","startTimeMs":0,"endTimeMs":2000}]""",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportOption(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
