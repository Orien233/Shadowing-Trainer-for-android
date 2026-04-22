package com.orien.shadowing.domain.usecase

import android.content.Context
import android.media.MediaExtractor
import android.util.Log
import com.orien.shadowing.data.local.MoonshineAsr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * Converts a raw audio/video file into the app's package format, then reuses the
 * normal package import flow.
 */
class ImportMediaUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importMaterialUseCase: ImportMaterialUseCase
) {
    companion object {
        private const val TAG = "ImportMediaUseCase"
        const val DISPUTED_SENTENCES_FILE_NAME = "disputed_sentences.json"
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp", "qt")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg", "aac", "flac", "opus")
        private val SUPPORTED_MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    }

    suspend fun importFromMediaFile(
        mediaFile: File,
        moonshineAsr: MoonshineAsr,
        displayName: String = mediaFile.name,
        mimeType: String? = null,
        onProgress: ImportProgressListener = {}
    ): ImportMaterialUseCase.ImportResult = withContext(Dispatchers.IO) {
        if (!mediaFile.exists() || !mediaFile.isFile) {
            return@withContext ImportMaterialUseCase.ImportResult.Error(
                "Media file not found: ${mediaFile.absolutePath}"
            )
        }

        onProgress.report(0.05f, "Validating media...")
        if (!isSupportedMediaFile(mediaFile, mimeType)) {
            return@withContext ImportMaterialUseCase.ImportResult.Error(
                "Unsupported media file: ${displayName.ifBlank { mediaFile.name }}"
            )
        }

        onProgress.report(0.12f, "Initializing speech model...")
        val ready = moonshineAsr.initialize()
        if (!ready) {
            val detail = moonshineAsr.getLastInitErrorMessage()
            return@withContext ImportMaterialUseCase.ImportResult.Error(
                if (detail.isNullOrBlank()) {
                    "Moonshine model is not available, so raw media cannot be processed yet."
                } else {
                    "Moonshine initialization failed: $detail"
                }
            )
        }

        val packageDir = File(context.cacheDir, "generated_package_${System.currentTimeMillis()}")
        try {
            packageDir.mkdirs()

            onProgress.report(0.22f, "Preparing media package...")
            val packagedMediaFile = File(packageDir, buildPackagedMediaName(displayName, mediaFile))
            mediaFile.copyTo(packagedMediaFile, overwrite = true)
            Log.i(
                TAG,
                "Copied media for import: source=${mediaFile.absolutePath}, package=${packagedMediaFile.absolutePath}"
            )

            onProgress.report(0.4f, "Analyzing media...")
            lateinit var transcript: MoonshineAsr.AsrResult
            val transcribeElapsedMs = measureTimeMillis {
                transcript = moonshineAsr.transcribe(packagedMediaFile.absolutePath)
            }
            Log.i(
                TAG,
                "ASR finished for ${packagedMediaFile.name}: elapsedMs=$transcribeElapsedMs, " +
                    "lineCount=${transcript.lines.size}, disputedCount=${transcript.disputedLines.size}, " +
                    "error=${transcript.errorMessage}"
            )
            if (transcript.errorMessage != null) {
                return@withContext ImportMaterialUseCase.ImportResult.Error(
                    "Failed to process media: ${transcript.errorMessage}"
                )
            }
            val transcriptLines = transcript.lines.filter { line ->
                line.text.isNotBlank() && line.endTimeMs > line.startTimeMs
            }.sortedBy { it.startTimeMs }
            val disputedLines = transcript.disputedLines.filter { line ->
                line.text.isNotBlank() && line.endTimeMs > line.startTimeMs
            }.sortedBy { it.startTimeMs }

            if (transcriptLines.isEmpty()) {
                return@withContext ImportMaterialUseCase.ImportResult.Error(
                    "No speech segments were detected in ${displayName.ifBlank { mediaFile.name }}."
                )
            }

            onProgress.report(0.72f, "Generating material package...")
            writeMetaJson(
                packageDir = packageDir,
                title = buildMaterialTitle(displayName),
                type = detectMaterialType(packagedMediaFile, mimeType),
                language = "en"
            )
            writeSentencesJson(packageDir, transcriptLines)
            if (disputedLines.isNotEmpty()) {
                writeDisputedSentencesJson(packageDir, disputedLines)
            }

            lateinit var importResult: ImportMaterialUseCase.ImportResult
            val packageImportElapsedMs = measureTimeMillis {
                importResult = importMaterialUseCase.importFromDirectory(packageDir) { progress ->
                    onProgress.report(
                        fraction = 0.8f + progress.fraction * 0.2f,
                        message = progress.message
                    )
                }
            }
            Log.i(
                TAG,
                "Package import finished for ${packagedMediaFile.name}: elapsedMs=$packageImportElapsedMs, result=$importResult"
            )
            if (importResult is ImportMaterialUseCase.ImportResult.Success) {
                onProgress.report(1f, "Import complete.")
            }
            return@withContext importResult
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            return@withContext ImportMaterialUseCase.ImportResult.Error(
                "Failed to process media: ${error.message ?: "unknown error"}"
            )
        } finally {
            packageDir.deleteRecursively()
        }
    }

    private fun writeMetaJson(
        packageDir: File,
        title: String,
        type: String,
        language: String
    ) {
        val meta = buildJsonObject {
            put("title", title)
            put("type", type)
            put("language", language)
        }
        File(packageDir, "meta.json").writeText(meta.toString())
    }

    private fun writeSentencesJson(
        packageDir: File,
        lines: List<MoonshineAsr.AsrLine>
    ) {
        val sentenceArray = buildJsonArray {
            lines.forEachIndexed { index, line ->
                add(
                    buildJsonObject {
                        put("index", index)
                        put("textOriginal", line.text)
                        put("startTimeMs", line.startTimeMs)
                        put("endTimeMs", line.endTimeMs)
                    }
                )
            }
        }
        File(packageDir, "sentences.json").writeText(
            Json.encodeToString(JsonArray.serializer(), sentenceArray)
        )
    }

    private fun writeDisputedSentencesJson(
        packageDir: File,
        lines: List<MoonshineAsr.AsrLine>
    ) {
        val sentenceArray = buildJsonArray {
            lines.forEachIndexed { index, line ->
                add(
                    buildJsonObject {
                        put("index", index)
                        put("textOriginal", line.text)
                        put("startTimeMs", line.startTimeMs)
                        put("endTimeMs", line.endTimeMs)
                    }
                )
            }
        }
        File(packageDir, DISPUTED_SENTENCES_FILE_NAME).writeText(
            Json.encodeToString(JsonArray.serializer(), sentenceArray)
        )
    }

    private fun buildMaterialTitle(displayName: String): String {
        val baseName = displayName.substringBeforeLast('.', displayName).trim()
        val normalized = baseName.replace(Regex("[_\\-]+"), " ").trim()
        return normalized.ifBlank { "Imported media" }
    }

    private fun buildPackagedMediaName(displayName: String, sourceFile: File): String {
        val extension = displayName.substringAfterLast('.', "").ifBlank { sourceFile.extension }
        val safeBaseName = displayName
            .substringBeforeLast('.', displayName)
            .ifBlank { "source_media" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "source_media" }

        return if (extension.isBlank()) {
            safeBaseName
        } else {
            "$safeBaseName.${extension.lowercase()}"
        }
    }

    private fun detectMaterialType(file: File, mimeType: String?): String {
        val trackDetectedType = detectMaterialTypeFromTracks(file)
        return when {
            trackDetectedType != null -> trackDetectedType
            mimeType?.startsWith("video/") == true -> "video"
            mimeType?.startsWith("audio/") == true -> "audio"
            file.extension.lowercase() in VIDEO_EXTENSIONS -> "video"
            else -> "audio"
        }
    }

    private fun detectMaterialTypeFromTracks(file: File): String? {
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
                hasVideoTrack -> "video"
                hasAudioTrack -> "audio"
                else -> null
            }
        } catch (_: Throwable) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun isSupportedMediaFile(file: File, mimeType: String?): Boolean {
        return mimeType?.startsWith("audio/") == true ||
            mimeType?.startsWith("video/") == true ||
            file.extension.lowercase() in SUPPORTED_MEDIA_EXTENSIONS
    }
}
