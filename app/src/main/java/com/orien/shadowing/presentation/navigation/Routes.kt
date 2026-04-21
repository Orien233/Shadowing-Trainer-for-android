package com.orien.shadowing.presentation.navigation

import kotlinx.serialization.Serializable

@Serializable
object MaterialListRoute

@Serializable
data class SentenceListRoute(val materialId: Long)

@Serializable
data class TrainingRoute(val materialId: Long, val sentenceId: Long)
