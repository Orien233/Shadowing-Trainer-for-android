package com.orien.shadowing.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoonshineAsrFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun create(instanceId: String): MoonshineAsr {
        return MoonshineAsr(
            context = context,
            instanceId = instanceId
        )
    }
}
