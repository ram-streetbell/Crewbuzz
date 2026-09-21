package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.sin

class CrewBuzzAlertService : Service() {

    companion object {
        const val ACTION_START_CALL = "com.crewbuzz.START_CALL"
        const val ACTION_SNOOZE = "com.crewbuzz.SNOOZE"
        const val ACTION_ATTEND = "com.crewbuzz.ATTEND"
        const val ACTION_RESET = "com.crewbuzz.RESET"

        const val EXTRA_TABLE = "table"
        const val SNOOZE_MS = 30_000L

        private const val CHANNEL_ID = "crewbuzz_alert_service"
        private const val NOTIFICATION_ID = 4101
    }

    private val handler = Handler(Looper.getMainLooper())
    private val activeCalls = mutableSetOf<String>()
    private val snoozedUntil = mutableMapOf<String, Long>()

    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    private val snoozeCheck = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            snoozedUntil.entries.removeAll { it.value <= now }
            updatePlayback()
            if (activeCalls.isNotEmpty()) {
                handler.postDelayed(this, 500L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                val table = intent.getStringExtra(EXTRA_TABLE)?.trim()
                if (!table.isNullOrEmpty()) {
                    activeCalls.add(table)
                    snoozedUntil.remove(table)
                }
            }

            ACTION_SNOOZE -> {
                val table = intent.getStringExtra(EXTRA_TABLE)?.trim()
                if (!table.isNullOrEmpty() && activeCalls.contains(table)) {
                    snoozedUntil[table] = System.currentTimeMillis() + SNOOZE_MS
                }
            }

            ACTION_ATTEND -> {
                val table = intent.getStringExtra(EXTRA_TABLE)?.trim()
                if (!table.isNullOrEmpty()) {
                    activeCalls.remove(table)
                    snoozedUntil.remove(table)
                }
            }

            ACTION_RESET -> {
                activeCalls.clear()
                snoozedUntil.clear()
            }
        }

        if (activeCalls.isNotEmpty()) {
            startAsForeground()
            updatePlayback()
            handler.removeCallbacks(snoozeCheck)
            handler.post(snoozeCheck)
        } else {
            stopPlayback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.example.R.drawable.ic_crewbuzz)
            .setContentTitle("CrewBuzz active")
            .setContentText("Waiting for table service requests")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updatePlayback() {
        val now = System.currentTimeMillis()
        val shouldRing = activeCalls.any { (snoozedUntil[it] ?: 0L) <= now }

        if (shouldRing) {
            startPlayback()
        } else {
            stopPlayback()
        }
    }

    private fun startPlayback() {
        if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) return

        if (!requestAudioFocus()) return

        if (audioTrack == null) {
            audioTrack = createAlertTrack()
        }

        try {
            audioTrack?.play()
        } catch (_: Exception) {
            releaseAudio()
        }
    }

    private fun stopPlayback() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }
        abandonAudioFocus()
    }

    private fun createAlertTrack(): AudioTrack {
        val sampleRate = 44_100
        val durationMs = 1400
        val samples = sampleRate * durationMs / 1000
        val data = ShortArray(samples)

        for (i in 0 until samples) {
            val t = i.toDouble() / sampleRate
            val phase = t % 1.4
            val tone = when {
                phase < 0.28 -> 880.0
                phase < 0.38 -> 0.0
                phase < 0.66 -> 660.0
                phase < 0.76 -> 0.0
                phase < 1.04 -> 880.0
                phase < 1.14 -> 0.0
                else -> 660.0
            }

            if (tone == 0.0) {
                data[i] = 0
            } else {
                val envelope = when {
                    phase < 0.03 || phase in 0.28..0.31 || phase in 0.66..0.69 || phase in 1.04..1.07 -> 0.0
                    else -> 0.92
                }
                data[i] = (sin(2.0 * PI * tone * t) * Short.MAX_VALUE * envelope).toInt().toShort()
            }
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = android.media.AudioFormat.Builder()
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(data, 0, data.size)
        track.setLoopPoints(0, data.size, -1)
        track.setVolume(1.0f)
        return track
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        if (audioFocusGranted) return true

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        return if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change ->
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        audioFocusGranted = true
                        if (activeCalls.isNotEmpty()) updatePlayback()
                    } else if (
                        change == AudioManager.AUDIOFOCUS_LOSS ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        audioFocusGranted = false
                        stopPlayback()
                    }
                }
                .build()
            focusRequest = request
            audioFocusGranted = manager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            audioFocusGranted
        } else {
            @Suppress("DEPRECATION")
            audioFocusGranted = manager.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        audioFocusGranted = true
                        if (activeCalls.isNotEmpty()) updatePlayback()
                    } else {
                        audioFocusGranted = false
                        stopPlayback()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            audioFocusGranted
        }
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) return
        val manager = audioManager ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }

        audioFocusGranted = false
    }

    private fun releaseAudio() {
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        abandonAudioFocus()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CrewBuzz Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps CrewBuzz ready to receive table calls"
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeCalls.clear()
        snoozedUntil.clear()
        stopPlayback()
        releaseAudio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
