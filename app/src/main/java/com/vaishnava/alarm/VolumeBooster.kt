package com.vaishnava.alarm

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log

class VolumeBooster(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val TAG = "VolumeBooster"
    private val handler = Handler(Looper.getMainLooper())
    private var boostTask: Runnable? = null
    private var mediaPlayerRef: MediaPlayer? = null
    private var audioBooster: AudioBooster? = null

    companion object {
        const val MIN_VOLUME_PERCENT = 40
        const val BOOST_INTERVAL_MS = 300L
    }

    fun setMinimumVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val minVolume = (maxVolume * MIN_VOLUME_PERCENT) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, minVolume, 0)
            Log.d(TAG, "✅ Min: $minVolume (40%)")
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    fun boostVolumeGradually(
        mp: MediaPlayer?,
        enableGradualVolume: Boolean = true,
        enableAudioBoost: Boolean = true
    ) {
        if (mp == null) {
            Log.e(TAG, "MediaPlayer is null!")
            return
        }

        mediaPlayerRef = mp
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val minVolume = (maxVolume * MIN_VOLUME_PERCENT) / 100

        Log.d(TAG, "🎵 Starting alarm - gradualVolume=$enableGradualVolume, audioBoost=$enableAudioBoost")
        setMinimumVolume()
        mp.setVolume(0.4f, 0.4f)

        // Create AudioBooster only if enabled
        if (enableAudioBoost) {
            audioBooster = AudioBooster(mp, context)
        }

        var counter = 0
        var boostEnabled = !enableAudioBoost // Already enabled if boost is disabled

        boostTask = object : Runnable {
            override fun run() {
                try {
                    val player = mediaPlayerRef
                    if (player == null || !player.isPlaying) {
                        Log.d(TAG, "🛑 Stopped")
                        return
                    }

                    // Enable boost on first run when audio is actually playing
                    if (!boostEnabled) {
                        boostEnabled = true
                        if (enableAudioBoost) {
                            Log.d(TAG, "🎵 Audio playing - NOW enabling audio boost effects")
                            audioBooster?.enableAudioBoost()
                        }
                    }

                    if (!enableGradualVolume) {
                        // Skip gradual increase - jump directly to max volume
                        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
                        player.setVolume(1.0f, 1.0f)
                        audioBooster?.updateBoostForVolume(1.0f)
                        if (counter == 0) {
                            Log.d(TAG, "📢 Volume set to MAX (gradual disabled)")
                        }
                    } else {
                        // Normal gradual volume increase
                        val pos = counter % 80

                        if (pos < 40) {
                            val progress = pos / 40f
                            val deviceVol = (minVolume + (maxVolume - minVolume) * progress).toInt()
                            val mediaVol = (0.4f + 0.6f * progress).coerceIn(0.4f, 1.0f)

                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, deviceVol, 0)
                            player.setVolume(mediaVol, mediaVol)
                            audioBooster?.updateBoostForVolume(mediaVol)

                            if (counter % 8 == 0) {
                                val pct = (mediaVol * 100).toInt()
                                Log.d(TAG, "⬆️ UP $pct%")
                            }
                        } else {
                            val progress = (pos - 40) / 40f
                            val deviceVol = (maxVolume - (maxVolume - minVolume) * progress).toInt()
                            val mediaVol = (1.0f - 0.6f * progress).coerceIn(0.4f, 1.0f)

                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, deviceVol, 0)
                            player.setVolume(mediaVol, mediaVol)
                            audioBooster?.updateBoostForVolume(mediaVol)

                            if (counter % 8 == 0) {
                                val pct = (mediaVol * 100).toInt()
                                Log.d(TAG, "⬇️ DOWN $pct%")
                            }
                        }
                    }

                    counter++
                    handler.postDelayed(this, BOOST_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}")
                }
            }
        }

        handler.postDelayed(boostTask!!, BOOST_INTERVAL_MS)
    }

    fun syncBoostToCurrentVolume() {
        try {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

            if (maxVolume > 0) {
                // Calculate percentage (0.4 to 1.0 range)
                val volumePercent = (currentVolume.toFloat() / maxVolume).coerceIn(0.4f, 1.0f)
                audioBooster?.updateBoostForVolume(volumePercent)
                Log.d(TAG, "🔄 Boost synced to current volume: ${(volumePercent * 100).toInt()}%")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing boost: ${e.message}")
        }
    }

    fun stopBoost() {
        boostTask?.let { handler.removeCallbacks(it) }
        audioBooster?.release()
        audioBooster = null
        mediaPlayerRef = null
    }
}
