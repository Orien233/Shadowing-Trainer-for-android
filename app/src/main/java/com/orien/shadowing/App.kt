package com.orien.shadowing

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import com.orien.shadowing.data.local.g2p.G2pService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject
    lateinit var g2pService: G2pService

    override fun onCreate() {
        super.onCreate()

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) {
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching {
                g2pService.textToPhonemes("I ride a bike.")
            }.onSuccess { phonemes ->
                Log.d(TAG, "G2P smoke: I ride a bike. -> $phonemes")
            }.onFailure { error ->
                Log.e(TAG, "G2P smoke failed.", error)
            }
        }
    }

    companion object {
        private const val TAG = "ShadowingApp"
    }
}
