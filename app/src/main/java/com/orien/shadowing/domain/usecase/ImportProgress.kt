package com.orien.shadowing.domain.usecase

data class ImportProgress(
    val fraction: Float,
    val message: String
)

typealias ImportProgressListener = (ImportProgress) -> Unit

internal fun ImportProgressListener.report(fraction: Float, message: String) {
    invoke(
        ImportProgress(
            fraction = fraction.coerceIn(0f, 1f),
            message = message
        )
    )
}
