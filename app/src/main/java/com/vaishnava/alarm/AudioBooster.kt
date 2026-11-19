package com.vaishnava.alarm

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log

class AudioBooster(private val mediaPlayer: MediaPlayer?, private val context: Context?) {
    private val tag = "AudioBooster"
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var isBoostEnabled = false
    private var lastAppliedVolume = 0.4f

    companion object {
        const val BOOST_VOLUME_MULTIPLIER = 1.25f  // +25% volume boost when Audio Boost enabled
    }

    fun enableAudioBoost() {
        isBoostEnabled = true
        applyBoostToCurrentVolume()
        Log.d(tag, "✅ Audio Boost enabled - system volume boost active")
    }

    private fun applyBoostToCurrentVolume() {
        try {
            if (audioManager == null || !isBoostEnabled) {
                return
            }

            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)

            // Apply +25% boost to current system volume
            val boostedVolume = (currentVolume * BOOST_VOLUME_MULTIPLIER).toInt().coerceIn(0, maxVolume)

            if (boostedVolume != currentVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, boostedVolume, 0)
                Log.d(tag, "🔊 Audio Boost applied: system volume $currentVolume → $boostedVolume/$maxVolume")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error applying boost: ${e.message}")
        }
    }

    fun updateBoostForVolume(volumePercent: Float) {
        // Called by VolumeBooster as volume progresses
        // If Audio Boost is enabled, apply additional system volume boost
        lastAppliedVolume = volumePercent
        if (isBoostEnabled) {
            applyBoostToCurrentVolume()
        }
    }

    fun disableAudioBoost() {
        isBoostEnabled = false
        Log.d(tag, "Audio boost disabled - system volume now controlled by VolumeBooster only")
    }

    fun release() {
        disableAudioBoost()
    }
}
