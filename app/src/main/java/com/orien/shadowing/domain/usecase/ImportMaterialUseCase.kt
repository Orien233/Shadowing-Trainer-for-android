package com.orien.shadowing.domain.usecase

import android.content.Context
import android.media.MediaExtractor
import android.util.Log
import com.orien.shadowing.data.local.media.PlaybackAssetPreparer
import com.orien.shadowing.data.local.repository.MaterialRepository
import com.orien.shadowing.data.model.SentenceEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * Imports a pre-processed material package from a directory.
 */
class ImportMaterialUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val materialRepository: MaterialRepository
) {
    companion object {
        private const val TAG = "ImportMaterialUseCase"
        private val VIDEO_EXTENSIONS = setOf(
            "mp4",
            "mkv",
            "mov",
            "webm",
            "m4v",
            "3gp",
            "qt"
        )

        private val SUPPORTED_MEDIA_EXTENSIONS = setOf(
            "mp3",
            "wav",
            "m4a",
            "ogg",
            "aac",
            "flac",
            "opus",
            *VIDEO_EXTENSIONS.toTypedArray()
        )
    }

    private enum class MediaKind {
        AUDIO,
        VIDEO,
        UNKNOWN
    }

    sealed class ImportResult {
        data class Success(val materialId: Long, val sentenceCount: Int) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    suspend fun importFromDirectory(
        packageDir: File,
        onProgress: ImportProgressListener = {}
    ): ImportResult = withContext(Dispatchers.IO) {
        if (!packageDir.isDirectory) {
            return@withContext ImportResult.Error("Not a directory: ${packageDir.absolutePath}")
        }

        onProgress.report(0.05f, "Reading package...")

        val metaFile = File(packageDir, "meta.json")
        if (!metaFile.exists()) {
            return@withContext ImportResult.Error("Missing meta.json in ${packageDir.absolutePath}")
        }

        val meta = try {
            Json.parseToJsonElement(metaFile.readText()).jsonObject
        } catch (error: Exception) {
            return@withContext ImportResult.Error("Invalid meta.json: ${error.message}")
        }

        val title = meta["title"]?.jsonPrimitive?.content ?: packageDir.name
        val type = meta["type"]?.jsonPrimitive?.content ?: "audio"
        val language = meta["language"]?.jsonPrimitive?.content ?: "en"

        val sentencesFile = File(packageDir, "sentences.json")
        if (!sentencesFile.exists()) {
            return@withContext ImportResult.Error("Missing sentences.json in ${packageDir.absolutePath}")
        }

        val sentencesJson = try {
            Json.parseToJsonElement(sentencesFile.readText()).jsonArray
        } catch (error: Exception) {
            return@withContext ImportResult.Error("Invalid sentences.json: ${error.message}")
        }

        val mediaCandidates = packageDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.name != PlaybackAssetPreparer.FALLBACK_AUDIO_FILE_NAME &&
                    (
                    file.extension.lowercase() in SUPPORTED_MEDIA_EXTENSIONS ||
                        detectMediaKindFromTracks(file) != MediaKind.UNKNOWN
                    )
            }
            .orEmpty()
        val sourceMedia = selectPrimaryMediaFile(mediaCandidates, type)
        val sourceMediaKind = sourceMedia?.let(::detectMediaKind) ?: MediaKind.UNKNOWN
        val resolvedType = when (sourceMediaKind) {
            MediaKind.VIDEO -> "video"
            MediaKind.AUDIO -> "audio"
            MediaKind.UNKNOWN -> if (type.equals("video", ignoreCase = true)) "video" else "audio"
        }

        val storageRoot = getStorageRoot().apply { mkdirs() }
        var materialId: Long? = null

        try {
            onProgress.report(0.2f, "Creating material...")
            materialId = materialRepository.createMaterial(
                title = title,
                type = resolvedType,
                sourcePath = null,
                language = language
            )

            val persistedMaterialId = materialId
                ?: return@withContext ImportResult.Error("Failed to create material.")
            val materialDir = File(storageRoot, "materials/$persistedMaterialId").apply { mkdirs() }

            onProgress.report(0.4f, "Preparing playback assets...")
            val copiedSourceMedia = sourceMedia?.let { mediaFile ->
                copyFile(mediaFile, File(materialDir, mediaFile.name))
            }
            val copiedFallbackAudio = copyOptionalFile(
                sourceRoot = packageDir,
                targetRoot = materialDir,
                relativePath = PlaybackAssetPreparer.FALLBACK_AUDIO_FILE_NAME
            )
            val preparedAssets = copiedSourceMedia?.let { copiedMedia ->
                var assets: PlaybackAssetPreparer.PreparedAssets? = null
                val elapsedMs = measureTimeMillis {
                    assets = PlaybackAssetPreparer.prepare(
                        materialDir = materialDir,
                        sourceMediaFile = copiedMedia,
                        existingFallbackAudio = copiedFallbackAudio
                    )
                }
                Log.i(
                    TAG,
                    "Playback asset preparation finished for ${copiedMedia.name}: elapsedMs=$elapsedMs, " +
                        "usedCompatVideo=${assets?.usedCompatVideo == true}, " +
                        "primary=${assets?.primaryMediaFile?.name}, fallback=${assets?.fallbackAudioFile?.name}"
                )
                assets
            }
            copyOptionalFile(
                sourceRoot = packageDir,
                targetRoot = materialDir,
                relativePath = ImportMediaUseCase.DISPUTED_SENTENCES_FILE_NAME
            )

            val sourcePath = preparedAssets?.primaryMediaFile?.absolutePath
            val fallbackAudioPath = preparedAssets?.fallbackAudioFile?.absolutePath
            if (sourcePath != null || fallbackAudioPath != null) {
                materialRepository.getMaterial(persistedMaterialId)?.let { material ->
                    materialRepository.updateMaterial(
                        material.copy(
                            sourcePath = sourcePath,
                            fallbackAudioPath = fallbackAudioPath
                        )
                    )
                }
            }

            onProgress.report(0.8f, "Saving sentences...")
            val sentenceEntities = sentencesJson.mapIndexed { index, element ->
                val item = element.jsonObject
                val clipPath = item["clipFile"]?.jsonPrimitive?.content?.let { relativeClip ->
                    copyRelativeFile(packageDir, materialDir, relativeClip)?.absolutePath
                }

                SentenceEntity(
                    materialId = persistedMaterialId,
                    index = item["index"]?.jsonPrimitive?.int ?: index,
                    textOriginal = item["textOriginal"]?.jsonPrimitive?.content ?: "",
                    textZh = stringOrNull(item, "textZh"),
                    startTimeMs = longOrNull(item, "startTimeMs"),
                    endTimeMs = longOrNull(item, "endTimeMs"),
                    clipPath = clipPath
                )
            }

            materialRepository.insertSentences(sentenceEntities)
            onProgress.report(1f, "Import complete.")
            ImportResult.Success(persistedMaterialId, sentenceEntities.size)
        } catch (error: Throwable) {
            materialId?.let { cleanupPartialImport(it, storageRoot) }
            if (error is CancellationException) {
                throw error
            }
            ImportResult.Error(
                "Failed to import material: ${error.message ?: "unknown error"}"
            )
        }
    }

    private fun getStorageRoot(): File = File(context.filesDir, "shadowing_data")

    private suspend fun cleanupPartialImport(materialId: Long, storageRoot: File) {
        runCatching {
            materialRepository.deleteMaterial(materialId, storageRoot)
        }.onFailure { error ->
            Log.w(TAG, "Failed to clean up partial import $materialId", error)
        }
    }

    private fun copyRelativeFile(sourceRoot: File, targetRoot: File, relativePath: String): File? {
        val sourceFile = File(sourceRoot, relativePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return null
        }

        val normalizedRelativePath = relativePath.replace('/', File.separatorChar)
        val targetFile = File(targetRoot, normalizedRelativePath)
        return copyFile(sourceFile, targetFile)
    }

    private fun copyFile(source: File, target: File): File {
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun copyOptionalFile(sourceRoot: File, targetRoot: File, relativePath: String): File? {
        val sourceFile = File(sourceRoot, relativePath)
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return null
        }
        return copyFile(sourceFile, File(targetRoot, relativePath))
    }

    private fun selectPrimaryMediaFile(candidates: List<File>, declaredType: String): File? {
        if (candidates.isEmpty()) {
            return null
        }

        val kindByFile = candidates.associateWith(::detectMediaKind)
        candidates.firstOrNull { it.name.equals(PlaybackAssetPreparer.COMPAT_VIDEO_FILE_NAME, ignoreCase = true) }
            ?.let { compatVideo ->
                if (kindByFile[compatVideo] == MediaKind.VIDEO) {
                    return compatVideo
                }
            }
        val preferredKind = when {
            declaredType.equals("video", ignoreCase = true) -> MediaKind.VIDEO
            declaredType.equals("audio", ignoreCase = true) -> MediaKind.AUDIO
            else -> null
        }

        if (preferredKind != null) {
            candidates.firstOrNull { file -> kindByFile[file] == preferredKind }?.let { return it }
        }

        return candidates.firstOrNull { file -> kindByFile[file] == MediaKind.VIDEO }
            ?: candidates.firstOrNull { file -> kindByFile[file] == MediaKind.AUDIO }
            ?: candidates.first()
    }

    private fun detectMediaKind(file: File): MediaKind {
        val byTrack = detectMediaKindFromTracks(file)
        if (byTrack != MediaKind.UNKNOWN) {
            return byTrack
        }
        return if (isVideoFile(file)) MediaKind.VIDEO else MediaKind.AUDIO
    }

    private fun detectMediaKindFromTracks(file: File): MediaKind {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var hasAudioTrack = false
            var hasVideoTrack = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true -> hasVideoTrack = true
                    mime?.startsWith("audio/") == true -> hasAudioTrack = true
                }
                if (hasVideoTrack) {
                    break
                }
            }

            when {
                hasVideoTrack -> MediaKind.VIDEO
                hasAudioTrack -> MediaKind.AUDIO
                else -> MediaKind.UNKNOWN
            }
        } catch (_: Throwable) {
            MediaKind.UNKNOWN
        } finally {
            extractor.release()
        }
    }

    private fun isVideoFile(file: File): Boolean =
        file.extension.lowercase() in VIDEO_EXTENSIONS

    private fun stringOrNull(
        item: kotlinx.serialization.json.JsonObject,
        key: String
    ): String? = runCatching {
        item[key]?.jsonPrimitive?.content
    }.getOrNull()

    private fun longOrNull(
        item: kotlinx.serialization.json.JsonObject,
        key: String
    ): Long? = runCatching {
        item[key]?.jsonPrimitive?.long
    }.getOrNull()

}
