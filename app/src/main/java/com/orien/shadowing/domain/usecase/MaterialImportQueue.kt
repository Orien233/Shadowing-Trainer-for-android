package com.orien.shadowing.domain.usecase

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.orien.shadowing.data.local.MoonshineAsrFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ImportTaskStatus {
    QUEUED,
    RUNNING,
    FAILED
}

enum class ImportTaskKind {
    AUDIO,
    VIDEO,
    FOLDER,
    DEMO
}

data class ImportTaskSnapshot(
    val id: String,
    val title: String,
    val kind: ImportTaskKind,
    val status: ImportTaskStatus,
    val progress: Float?,
    val message: String,
    val createdAt: Long,
    val queuePosition: Int?,
    val activeTaskCount: Int?,
    val errorMessage: String?
)

@Singleton
class MaterialImportQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moonshineAsrFactory: MoonshineAsrFactory,
    private val importMediaUseCase: ImportMediaUseCase,
    private val importMaterialUseCase: ImportMaterialUseCase,
    private val demoMaterialGenerator: DemoMaterialGenerator
) {
    companion object {
        private const val MAX_CONCURRENT_IMPORTS = 2
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp", "qt")
    }

    private sealed interface ImportRequest {
        data class MediaUri(
            val uri: Uri,
            val displayName: String,
            val mimeType: String?
        ) : ImportRequest

        data class DirectoryUri(
            val uri: Uri,
            val displayName: String
        ) : ImportRequest

        data class LocalDirectory(
            val directory: File
        ) : ImportRequest

        data object Demo : ImportRequest
    }

    private data class TaskRecord(
        val id: String,
        val sequence: Long,
        val createdAt: Long,
        val title: String,
        val kind: ImportTaskKind,
        val request: ImportRequest,
        var status: ImportTaskStatus,
        var progress: Float?,
        var message: String,
        var errorMessage: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val records = mutableListOf<TaskRecord>()
    private val _tasks = MutableStateFlow<List<ImportTaskSnapshot>>(emptyList())
    val tasks: StateFlow<List<ImportTaskSnapshot>> = _tasks.asStateFlow()

    private var nextSequence = 0L
    private var activeImports = 0

    fun enqueueMediaUri(uri: Uri) {
        takePersistableReadPermission(uri)
        val document = DocumentFile.fromSingleUri(context, uri)
        val rawName = document?.name ?: uri.lastPathSegment ?: "Imported media"
        val mimeType = context.contentResolver.getType(uri)
        enqueue(
            title = normalizeMediaTitle(rawName),
            kind = resolveMediaKind(rawName, mimeType),
            request = ImportRequest.MediaUri(
                uri = uri,
                displayName = rawName,
                mimeType = mimeType
            )
        )
    }

    fun enqueueDirectoryUri(uri: Uri) {
        takePersistableReadPermission(uri)
        val document = DocumentFile.fromTreeUri(context, uri)
        val rawName = document?.name ?: uri.lastPathSegment ?: "Imported folder"
        enqueue(
            title = rawName.ifBlank { "Imported folder" },
            kind = ImportTaskKind.FOLDER,
            request = ImportRequest.DirectoryUri(
                uri = uri,
                displayName = rawName.ifBlank { "Imported folder" }
            )
        )
    }

    fun enqueueLocalDirectory(directory: File) {
        enqueue(
            title = directory.name.ifBlank { "Imported folder" },
            kind = ImportTaskKind.FOLDER,
            request = ImportRequest.LocalDirectory(directory)
        )
    }

    fun enqueueDemoImport() {
        enqueue(
            title = "Demo materials",
            kind = ImportTaskKind.DEMO,
            request = ImportRequest.Demo
        )
    }

    private fun enqueue(
        title: String,
        kind: ImportTaskKind,
        request: ImportRequest
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            nextSequence += 1
            records += TaskRecord(
                id = "import-$nextSequence-$now",
                sequence = nextSequence,
                createdAt = now,
                title = title,
                kind = kind,
                request = request,
                status = ImportTaskStatus.QUEUED,
                progress = null,
                message = "Waiting for an available slot...",
                errorMessage = null
            )
            publishLocked()
            startAvailableTasksLocked()
        }
    }

    private fun startAvailableTasksLocked() {
        while (activeImports < MAX_CONCURRENT_IMPORTS) {
            val next = records
                .filter { it.status == ImportTaskStatus.QUEUED }
                .minByOrNull { it.sequence }
                ?: return

            next.status = ImportTaskStatus.RUNNING
            next.progress = 0.02f
            next.message = "Starting import..."
            activeImports += 1
            publishLocked()

            val taskId = next.id
            scope.launch {
                processTask(taskId)
            }
        }
    }

    private suspend fun processTask(taskId: String) {
        try {
            val request = synchronized(lock) {
                records.firstOrNull { it.id == taskId }?.request
            } ?: return

            when (request) {
                is ImportRequest.MediaUri -> runMediaImport(taskId, request)
                is ImportRequest.DirectoryUri -> runDirectoryImport(taskId, request)
                is ImportRequest.LocalDirectory -> runLocalDirectoryImport(taskId, request)
                ImportRequest.Demo -> runDemoImport(taskId)
            }
        } catch (error: CancellationException) {
            failTask(taskId, "Import cancelled.")
            throw error
        } catch (error: Throwable) {
            failTask(taskId, error.message ?: "Unknown error")
        } finally {
            synchronized(lock) {
                activeImports = (activeImports - 1).coerceAtLeast(0)
                publishLocked()
                startAvailableTasksLocked()
            }
        }
    }

    private suspend fun runMediaImport(taskId: String, request: ImportRequest.MediaUri) {
        val copiedFile = File(
            context.cacheDir,
            "media_${System.currentTimeMillis()}_${resolveTempFileName(request.displayName, request.mimeType)}"
        )
        val moonshineAsr = moonshineAsrFactory.create("import-task:$taskId")

        try {
            updateTaskProgress(taskId, 0.05f, "Copying selected media...")
            copyUriToFile(request.uri, copiedFile) { fraction ->
                updateTaskProgress(
                    taskId = taskId,
                    fraction = 0.05f + fraction * 0.15f,
                    message = "Copying selected media..."
                )
            }

            val result = importMediaUseCase.importFromMediaFile(
                mediaFile = copiedFile,
                displayName = request.displayName,
                moonshineAsr = moonshineAsr,
                mimeType = request.mimeType
            ) { progress ->
                updateTaskProgress(
                    taskId = taskId,
                    fraction = 0.2f + progress.fraction * 0.8f,
                    message = progress.message
                )
            }
            handleImportResult(taskId, result)
        } finally {
            moonshineAsr.release()
            copiedFile.delete()
        }
    }

    private suspend fun runDirectoryImport(taskId: String, request: ImportRequest.DirectoryUri) {
        val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}_${sanitizeForPath(request.displayName)}")
        try {
            tempDir.mkdirs()
            updateTaskProgress(taskId, 0.05f, "Copying selected folder...")
            val root = DocumentFile.fromTreeUri(context, request.uri)
                ?: throw IllegalArgumentException("The selected folder is not valid.")
            if (!root.isDirectory) {
                throw IllegalArgumentException("The selected folder is not valid.")
            }

            copyDocumentTree(root, tempDir) { fraction ->
                updateTaskProgress(
                    taskId = taskId,
                    fraction = 0.05f + fraction * 0.25f,
                    message = "Copying selected folder..."
                )
            }

            val result = importMaterialUseCase.importFromDirectory(tempDir) { progress ->
                updateTaskProgress(
                    taskId = taskId,
                    fraction = 0.3f + progress.fraction * 0.7f,
                    message = progress.message
                )
            }
            handleImportResult(taskId, result)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun runLocalDirectoryImport(taskId: String, request: ImportRequest.LocalDirectory) {
        updateTaskProgress(taskId, 0.05f, "Reading package...")
        val result = importMaterialUseCase.importFromDirectory(request.directory) { progress ->
            updateTaskProgress(taskId, progress.fraction, progress.message)
        }
        handleImportResult(taskId, result)
    }

    private suspend fun runDemoImport(taskId: String) {
        updateTaskProgress(taskId, 0.05f, "Generating demo materials...")
        val result = demoMaterialGenerator.generateAndImport { progress ->
            updateTaskProgress(taskId, progress.fraction, progress.message)
        }
        handleImportResult(taskId, result)
    }

    private fun handleImportResult(taskId: String, result: ImportMaterialUseCase.ImportResult) {
        when (result) {
            is ImportMaterialUseCase.ImportResult.Success -> completeTask(taskId)
            is ImportMaterialUseCase.ImportResult.Error -> failTask(taskId, result.message)
        }
    }

    private fun updateTaskProgress(taskId: String, fraction: Float, message: String) {
        synchronized(lock) {
            val record = records.firstOrNull { it.id == taskId } ?: return
            if (record.status == ImportTaskStatus.FAILED) {
                return
            }
            record.status = ImportTaskStatus.RUNNING
            record.progress = fraction.coerceIn(0f, 1f)
            record.message = message
            publishLocked()
        }
    }

    private fun failTask(taskId: String, errorMessage: String) {
        synchronized(lock) {
            val record = records.firstOrNull { it.id == taskId } ?: return
            record.status = ImportTaskStatus.FAILED
            record.progress = null
            record.message = "Import failed."
            record.errorMessage = errorMessage.ifBlank { "Unknown error" }
            publishLocked()
        }
    }

    private fun completeTask(taskId: String) {
        synchronized(lock) {
            records.removeAll { it.id == taskId }
            publishLocked()
        }
    }

    private fun publishLocked() {
        val activeRecords = records
            .filter { it.status != ImportTaskStatus.FAILED }
            .sortedBy { it.sequence }
        val activeCount = activeRecords.size
        val queuePositions = activeRecords
            .mapIndexed { index, record -> record.id to index + 1 }
            .toMap()

        val visibleRecords = buildList {
            addAll(activeRecords)
            addAll(
                records
                    .filter { it.status == ImportTaskStatus.FAILED }
                    .sortedByDescending { it.createdAt }
            )
        }

        _tasks.value = visibleRecords.map { record ->
            ImportTaskSnapshot(
                id = record.id,
                title = record.title,
                kind = record.kind,
                status = record.status,
                progress = record.progress,
                message = record.message,
                createdAt = record.createdAt,
                queuePosition = queuePositions[record.id],
                activeTaskCount = if (record.status == ImportTaskStatus.FAILED) null else activeCount,
                errorMessage = record.errorMessage
            )
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun copyUriToFile(
        sourceUri: Uri,
        destinationFile: File,
        onProgress: (Float) -> Unit
    ) {
        destinationFile.parentFile?.mkdirs()
        val expectedBytes = context.contentResolver.openFileDescriptor(sourceUri, "r")
            ?.use { descriptor -> descriptor.statSize }
            ?.takeIf { it > 0L }

        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            destinationFile.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var copiedBytes = 0L
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount <= 0) {
                        break
                    }
                    output.write(buffer, 0, readCount)
                    if (expectedBytes != null) {
                        copiedBytes += readCount
                        onProgress((copiedBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        } ?: throw IllegalStateException("The selected media file could not be read.")

        onProgress(1f)
    }

    private fun copyDocumentTree(
        source: DocumentFile,
        destinationDir: File,
        onProgress: (Float) -> Unit
    ) {
        val totalFiles = countFiles(source).coerceAtLeast(1)
        var copiedFiles = 0

        fun copyNode(node: DocumentFile, targetDir: File) {
            node.listFiles().forEach { child ->
                val childName = child.name ?: return@forEach
                if (child.isDirectory) {
                    copyNode(child, File(targetDir, childName).apply { mkdirs() })
                } else if (child.isFile) {
                    val destinationFile = File(targetDir, childName)
                    destinationFile.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        destinationFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("Failed to copy $childName.")

                    copiedFiles += 1
                    onProgress((copiedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f))
                }
            }
        }

        onProgress(0f)
        copyNode(source, destinationDir)
        onProgress(1f)
    }

    private fun countFiles(source: DocumentFile): Int {
        if (!source.isDirectory) {
            return if (source.isFile) 1 else 0
        }
        return source.listFiles().sumOf { child -> countFiles(child) }
    }

    private fun normalizeMediaTitle(displayName: String): String {
        val baseName = displayName.substringBeforeLast('.', displayName).trim()
        val normalized = baseName.replace(Regex("[_\\-]+"), " ").trim()
        return normalized.ifBlank { "Imported media" }
    }

    private fun resolveMediaKind(displayName: String, mimeType: String?): ImportTaskKind {
        return when {
            mimeType?.startsWith("video/") == true -> ImportTaskKind.VIDEO
            mimeType?.startsWith("audio/") == true -> ImportTaskKind.AUDIO
            displayName.substringAfterLast('.', "").lowercase(Locale.getDefault()) in VIDEO_EXTENSIONS -> ImportTaskKind.VIDEO
            else -> ImportTaskKind.AUDIO
        }
    }

    private fun resolveTempFileName(displayName: String, mimeType: String?): String {
        val extension = displayName.substringAfterLast('.', "").ifBlank {
            mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.orEmpty()
        }
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .ifBlank { "imported_media" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "imported_media" }

        return if (extension.isBlank()) {
            baseName
        } else {
            "$baseName.${extension.lowercase(Locale.getDefault())}"
        }
    }

    private fun sanitizeForPath(input: String): String {
        return input
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "import" }
    }
}
