package com.orien.shadowing.data.local

import android.media.AudioFormat
import android.media.AudioRecord
import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Recorder for user shadowing attempts.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val WAV_HEADER_SIZE = 44
        private const val CACHE_RECORDING_DIR = "training_attempt_recordings"
    }

    private var recorder: AudioRecord? = null
    private var currentFile: File? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var keepRecording = false

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var startTimeMs: Long = 0L
    private var durationHandler: android.os.Handler? = null
    private var durationRunnable: Runnable? = null

    @Synchronized
    fun startRecording(materialId: Long, sentenceId: Long): String {
        check(!_isRecording.value) { "A recording is already in progress." }

        val recordingsDir = File(context.cacheDir, CACHE_RECORDING_DIR)
        recordingsDir.mkdirs()
        recordingsDir.listFiles()?.forEach { existing ->
            if (existing.isFile) {
                runCatching { existing.delete() }
            }
        }

        val outputFile = File(recordingsDir, "attempt_${materialId}_${sentenceId}.wav")
        currentFile = outputFile

        val bufferSize = resolveBufferSize()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            currentFile = null
            throw IllegalStateException("Failed to initialize AudioRecord.")
        }

        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("AudioRecord failed to enter recording state.")
            }
        } catch (error: Exception) {
            audioRecord.release()
            currentFile = null
            throw error
        }

        recorder = audioRecord
        keepRecording = true
        startWriterThread(outputFile, audioRecord, bufferSize)

        _isRecording.value = true
        _recordingDurationMs.value = 0L
        startTimeMs = System.currentTimeMillis()
        startDurationUpdates()
        return outputFile.absolutePath
    }

    @Synchronized
    fun stopRecording(): String? {
        stopDurationUpdates()
        val finishedFile = currentFile

        stopRecorder(deleteOutput = false)

        return finishedFile
            ?.takeIf { it.exists() && it.length() > WAV_HEADER_SIZE }
            ?.absolutePath
    }

    @Synchronized
    fun cancelRecording() {
        stopDurationUpdates()
        stopRecorder(deleteOutput = true)
    }

    private fun startDurationUpdates() {
        stopDurationUpdates()
        durationHandler = android.os.Handler(android.os.Looper.getMainLooper())
        durationRunnable = object : Runnable {
            override fun run() {
                _recordingDurationMs.value = System.currentTimeMillis() - startTimeMs
                durationHandler?.postDelayed(this, 100L)
            }
        }
        durationHandler?.post(durationRunnable!!)
    }

    private fun stopDurationUpdates() {
        durationRunnable?.let { runnable ->
            durationHandler?.removeCallbacks(runnable)
        }
        durationRunnable = null
        durationHandler = null
    }

    private fun resolveBufferSize(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "Invalid AudioRecord min buffer size: $minBufferSize" }
        return max(minBufferSize, SAMPLE_RATE)
    }

    private fun startWriterThread(
        outputFile: File,
        audioRecord: AudioRecord,
        bufferSize: Int
    ) {
        val buffer = ByteArray(bufferSize)
        recordingThread = Thread(
            {
                var dataBytes = 0L
                try {
                    FileOutputStream(outputFile).use { output ->
                        output.write(ByteArray(WAV_HEADER_SIZE))

                        while (keepRecording) {
                            val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                            when {
                                bytesRead > 0 -> {
                                    output.write(buffer, 0, bytesRead)
                                    dataBytes += bytesRead
                                }

                                bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                                    throw IllegalStateException("AudioRecord became unavailable.")
                                }

                                bytesRead < 0 && keepRecording -> {
                                    throw IllegalStateException("AudioRecord read failed: $bytesRead")
                                }
                            }
                        }

                        output.fd.sync()
                    }

                    if (dataBytes > 0L) {
                        writeWavHeader(outputFile, dataBytes)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to persist WAV recording.", error)
                    outputFile.delete()
                }
            },
            "shadowing-audio-recorder"
        ).apply { start() }
    }

    private fun stopRecorder(deleteOutput: Boolean) {
        keepRecording = false

        val activeRecorder = recorder
        try {
            activeRecorder?.stop()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop failed.", error)
        }

        recordingThread?.let { thread ->
            runCatching { thread.join(2_000L) }
        }
        recordingThread = null
        activeRecorder?.release()
        recorder = null

        if (deleteOutput) {
            currentFile?.delete()
        }

        currentFile = null
        _isRecording.value = false
        _recordingDurationMs.value = 0L
    }

    private fun writeWavHeader(outputFile: File, dataBytes: Long) {
        RandomAccessFile(outputFile, "rw").use { wavFile ->
            val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
            val blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8
            val totalDataLen = dataBytes + 36

            wavFile.seek(0L)
            wavFile.writeBytes("RIFF")
            wavFile.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
            wavFile.writeBytes("WAVE")
            wavFile.writeBytes("fmt ")
            wavFile.writeInt(Integer.reverseBytes(16))
            wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            wavFile.writeShort(java.lang.Short.reverseBytes(CHANNEL_COUNT.toShort()).toInt())
            wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            wavFile.writeInt(Integer.reverseBytes(byteRate))
            wavFile.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
            wavFile.writeShort(java.lang.Short.reverseBytes(BITS_PER_SAMPLE.toShort()).toInt())
            wavFile.writeBytes("data")
            wavFile.writeInt(Integer.reverseBytes(dataBytes.toInt()))
        }
    }
}
