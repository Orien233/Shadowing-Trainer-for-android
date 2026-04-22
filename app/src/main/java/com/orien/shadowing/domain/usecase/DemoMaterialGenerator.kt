package com.orien.shadowing.domain.usecase

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates a few demo material packages for smoke testing the app flow.
 */
@Singleton
class DemoMaterialGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importMaterialUseCase: ImportMaterialUseCase
) {
    suspend fun generateAndImport(
        onProgress: ImportProgressListener = {}
    ): ImportMaterialUseCase.ImportResult {
        onProgress.report(0.05f, "Generating demo materials...")
        val demoRoot = File(context.cacheDir, "demo_packages").apply { mkdirs() }

        val packageSpecs = listOf(
            "Lesson 1 - Greetings" to createDemoPackage(
                parentDir = demoRoot,
                title = "Lesson 1 - Greetings",
                sentences = listOf(
                    "Hello, how are you today?" to "Greeting",
                    "I am doing great, thanks for asking." to "Reply",
                    "What brings you here?" to "Question",
                    "Nice to meet you." to "Greeting",
                    "Have a wonderful day." to "Farewell"
                )
            ),
            "Lesson 2 - Daily Routine" to createDemoPackage(
                parentDir = demoRoot,
                title = "Lesson 2 - Daily Routine",
                sentences = listOf(
                    "I usually wake up at seven in the morning." to "Morning routine",
                    "After breakfast, I take the subway to work." to "Commute",
                    "Lunch break is at noon, and I often eat with my colleagues." to "Lunch",
                    "In the evening, I like to read or watch a movie." to "Evening hobby",
                    "I try to go to bed before eleven every night." to "Sleep"
                )
            ),
            "Lesson 3 - Meeting Phrases" to createDemoPackage(
                parentDir = demoRoot,
                title = "Lesson 3 - Meeting Phrases",
                sentences = listOf(
                    "Let us get started with the agenda." to "Opening",
                    "Could you please elaborate on that point?" to "Clarification",
                    "I would like to propose a different approach." to "Suggestion",
                    "What are your thoughts on this matter?" to "Discussion",
                    "Thank you all for your contributions today." to "Closing"
                )
            )
        )

        val totalPackages = packageSpecs.size.coerceAtLeast(1)
        val results = buildList {
            packageSpecs.forEachIndexed { index, (title, packageDir) ->
                val packageStart = 0.15f + index * (0.8f / totalPackages)
                val packageSpan = 0.8f / totalPackages
                add(
                    importMaterialUseCase.importFromDirectory(packageDir) { progress ->
                        onProgress.report(
                            fraction = packageStart + progress.fraction * packageSpan,
                            message = "Importing $title..."
                        )
                    }
                )
            }
        }
        val successes = results.filterIsInstance<ImportMaterialUseCase.ImportResult.Success>()
        return if (successes.isNotEmpty()) {
            onProgress.report(1f, "Demo materials ready.")
            ImportMaterialUseCase.ImportResult.Success(
                materialId = successes.first().materialId,
                sentenceCount = successes.sumOf { it.sentenceCount }
            )
        } else {
            ImportMaterialUseCase.ImportResult.Error("Failed to generate demo materials.")
        }
    }

    private fun createDemoPackage(
        parentDir: File,
        title: String,
        sentences: List<Pair<String, String>>
    ): File {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val packageDir = File(parentDir, slug).apply { mkdirs() }

        val meta = buildJsonObject {
            put("title", title)
            put("type", "audio")
            put("language", "en")
        }
        File(packageDir, "meta.json").writeText(meta.toString())

        val sentenceArray = buildJsonArray {
            sentences.forEachIndexed { index, (original, note) ->
                add(
                    buildJsonObject {
                        put("index", index)
                        put("textOriginal", original)
                        put("textZh", note)
                        put("startTimeMs", index * 5000)
                        put("endTimeMs", (index + 1) * 5000 - 500)
                    }
                )
            }
        }
        File(packageDir, "sentences.json").writeText(
            Json.encodeToString(JsonArray.serializer(), sentenceArray)
        )

        return packageDir
    }
}
