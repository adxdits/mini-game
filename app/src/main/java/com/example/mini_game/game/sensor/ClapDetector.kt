package com.example.mini_game.game.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Detects loud sound spikes (claps) via the microphone.
 * Calls [onClap] on the main thread when amplitude crosses the threshold.
 */
class ClapDetector(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CLAP_THRESHOLD = 28000      // very loud only (max 32767)
        private const val SPIKE_RATIO = 10f           // must be 10x louder than ambient
        private const val MIN_CLAP_INTERVAL_MS = 700L
        private const val NOISE_SMOOTHING = 0.08f
        private const val MIN_NOISE_FLOOR = 800f      // never let floor collapse to 0
    }

    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastClapTime = 0L
    private var noiseFloor = 1000f                    // running ambient amplitude

    var onClap: (() -> Unit)? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasPermission()) return
        if (job != null) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).also { it.startRecording() }
        } catch (e: SecurityException) {
            return
        }

        val buffer = ShortArray(bufferSize)
        job = scope.launch {
            while (record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = record?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    var maxAmp = 0
                    for (i in 0 until read) {
                        val a = abs(buffer[i].toInt())
                        if (a > maxAmp) maxAmp = a
                    }
                    val isSpike = maxAmp > CLAP_THRESHOLD &&
                                  maxAmp > noiseFloor * SPIKE_RATIO
                    if (isSpike) {
                        val now = System.currentTimeMillis()
                        if (now - lastClapTime > MIN_CLAP_INTERVAL_MS) {
                            lastClapTime = now
                            onClap?.invoke()
                        }
                    } else {
                        // Update running noise floor only with non-spike frames
                        noiseFloor += (maxAmp - noiseFloor) * NOISE_SMOOTHING
                        if (noiseFloor < MIN_NOISE_FLOOR) noiseFloor = MIN_NOISE_FLOOR
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            record?.stop()
            record?.release()
        } catch (_: Exception) {}
        record = null
    }
}
