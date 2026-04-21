package com.orien.shadowing.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

/**
 * Converts a raw audio/video file into the app's package format, then reuses the
 * normal package import flow.
 */
class ImportMediaUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moonshineAsr: com.orien.shadowing.data.local.MoonshineAsr,
    private val importMaterialUseCase: ImportMaterialUseCase
) {
    suspend fun importFromMediaFile(
        mediaFile: File,
        displayName: String = mediaFile.name,
        mimeType: String? = null
    ): ImportMaterialUseCase.ImportResult {
        if (!mediaFile.exists() || !mediaFile.isFile) {
            return ImportMaterialUseCase.ImportResult.Error(
                "Media file not found: ${mediaFile.absolutePath}"
            )
        }

        if (!isSupportedMediaFile(mediaFile, mimeType)) {
            return ImportMaterialUseCase.ImportResult.Error(
                "Unsupported media file: ${displayName.ifBlank { mediaFile.name }}"
            )
        }

        val ready = moonshineAsr.initialize()
        if (!ready) {
            val detail = moonshineAsr.getLastInitErrorMessage()
            return ImportMaterialUseCase.ImportResult.Error(
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

            val packagedMediaFile = File(packageDir, buildPackagedMediaName(displayName, mediaFile))
            mediaFile.copyTo(packagedMediaFile, overwrite = true)

            val transcript = moonshineAsr.transcribe(packagedMediaFile.absolutePath)
            if (transcript.errorMessage != null) {
                return ImportMaterialUseCase.ImportResult.Error(
                    "Failed to process media: ${transcript.errorMessage}"
                )
            }
            val transcriptLines = transcript.lines.filter { line ->
                line.text.isNotBlank() && line.endTimeMs > line.startTimeMs
            }

            if (transcriptLines.isEmpty()) {
                return ImportMaterialUseCase.ImportResult.Error(
                    "No speech segments were detected in ${displayName.ifBlank { mediaFile.name }}."
                )
            }

            writeMetaJson(
                packageDir = packageDir,
                title = buildMaterialTitle(displayName),
                type = detectMaterialType(mediaFile, mimeType),
                language = "en"
            )
            writeSentencesJson(packageDir, transcriptLines)

            return importMaterialUseCase.importFromDirectory(packageDir)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            return ImportMaterialUseCase.ImportResult.Error(
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
        lines: List<com.orien.shadowing.data.local.MoonshineAsr.AsrLine>
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
        return when {
            mimeType?.startsWith("video/") == true -> "video"
            mimeType?.startsWith("audio/") == true -> "audio"
            file.extension.lowercase() in VIDEO_EXTENSIONS -> "video"
            else -> "audio"
        }
    }

    private fun isSupportedMediaFile(file: File, mimeType: String?): Boolean {
        return mimeType?.startsWith("audio/") == true ||
            mimeType?.startsWith("video/") == true ||
            file.extension.lowercase() in SUPPORTED_MEDIA_EXTENSIONS
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg", "aac", "flac", "opus")
        private val SUPPORTED_MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
    }
}
