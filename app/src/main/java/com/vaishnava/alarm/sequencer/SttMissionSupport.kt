package com.vaishnava.alarm.sequencer

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

object SttMissionSupport {
    const val MISSION_ID = "stt"
    const val DEFAULT_TARGET_PHRASE = "Hare Krishna"
    private const val PREFS_NAME = "stt_mission_prefs"
    private const val PENDING_PHRASE_KEY = "pending_stt_phrase"

    private var pendingTargetPhrase: String? = null

    fun isSpeechRecognizerAvailable(context: Context): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun createSpeechRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // Only the top hypothesis is used. Asking for alternatives lets the recognizer
            // offer a predicted completion of a common phrase ("hare" -> "hare krishna"),
            // which would pass the match before the user has actually said it.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Don't end the utterance on the natural pause between words.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
    }

    fun getTargetPhrase(context: Context, alarmId: Int): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString("phrase_alarm_$alarmId", null) ?: DEFAULT_TARGET_PHRASE
        } catch (_: Exception) {
            DEFAULT_TARGET_PHRASE
        }
    }

    fun saveTargetPhrase(context: Context, alarmId: Int, phrase: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("phrase_alarm_$alarmId", phrase.ifBlank { DEFAULT_TARGET_PHRASE }).apply()
        } catch (e: Exception) {
            Log.e("SttMissionSupport", "Failed to save target phrase: ${e.message}")
        }
    }

    fun savePendingTargetPhrase(context: Context, phrase: String) {
        pendingTargetPhrase = phrase.ifBlank { DEFAULT_TARGET_PHRASE }
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PENDING_PHRASE_KEY, pendingTargetPhrase).apply()
        } catch (_: Exception) {}
    }

    fun commitPendingTargetPhrase(context: Context, alarmId: Int) {
        val phraseToSave = pendingTargetPhrase ?: try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PENDING_PHRASE_KEY, null)
        } catch (_: Exception) { null } ?: DEFAULT_TARGET_PHRASE

        saveTargetPhrase(context, alarmId, phraseToSave)
        pendingTargetPhrase = null
    }

    /**
     * Lowercases, drops punctuation and collapses whitespace. Letters and digits of any
     * script are kept, so non-English phrases survive normalization.
     */
    private fun normalizePhrase(text: String): String =
        text.lowercase(Locale.getDefault())
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun resolveTarget(targetPhrase: String?): String =
        targetPhrase?.ifBlank { DEFAULT_TARGET_PHRASE } ?: DEFAULT_TARGET_PHRASE

    /**
     * The target split for display, keeping the user's original casing. Tokens with no
     * letter or digit are dropped so this stays index-aligned with [matchedWordCount].
     */
    fun displayWords(targetPhrase: String?): List<String> =
        resolveTarget(targetPhrase)
            .trim()
            .split(Regex("\\s+"))
            .filter { token -> token.any { it.isLetterOrDigit() } }

    /**
     * How many leading target words have been spoken so far, in order. Drives the
     * per-word progress indicator from partial recognition results.
     */
    fun matchedWordCount(recognizedText: String?, targetPhrase: String?): Int {
        if (recognizedText.isNullOrBlank()) return 0
        val targetWords = normalizePhrase(resolveTarget(targetPhrase))
            .split(" ").filter { it.isNotBlank() }
        val recognizedWords = normalizePhrase(recognizedText)
            .split(" ").filter { it.isNotBlank() }
        if (targetWords.isEmpty()) return 0

        var matched = 0
        for (spoken in recognizedWords) {
            if (matched < targetWords.size && spoken == targetWords[matched]) {
                matched++
            }
        }
        // A single joined token ("harekrishna") counts as the whole phrase.
        if (matched == 0 && recognizedWords.size == 1 &&
            recognizedWords[0] == targetWords.joinToString("")
        ) {
            return targetWords.size
        }
        return matched
    }

    /**
     * Matching rule: every word of the target must be spoken, in order.
     *
     * Extra words around or between them are tolerated, because recognizers routinely add
     * filler ("um hare krishna please" passes). A partial phrase does not - saying only
     * "hare" against "Hare Krishna" fails, and a blank target never acts as a wildcard.
     */
    fun matches(recognizedText: String?, targetPhrase: String?): Boolean {
        if (recognizedText.isNullOrBlank()) return false
        val target = targetPhrase?.ifBlank { DEFAULT_TARGET_PHRASE } ?: DEFAULT_TARGET_PHRASE

        val cleanRecognized = normalizePhrase(recognizedText)
        val cleanTarget = normalizePhrase(target)

        if (cleanTarget.isEmpty() || cleanRecognized.isEmpty()) return false
        if (cleanRecognized == cleanTarget) return true

        // Recognizers sometimes join a phrase into one token ("harekrishna"). This still
        // requires every character of the full phrase, so it cannot admit a partial one.
        if (cleanRecognized.replace(" ", "") == cleanTarget.replace(" ", "")) return true

        val targetWords = cleanTarget.split(" ").filter { it.isNotBlank() }
        val recognizedWords = cleanRecognized.split(" ").filter { it.isNotBlank() }

        if (targetWords.isEmpty()) return false
        if (recognizedWords.size < targetWords.size) return false

        var matched = 0
        for (spoken in recognizedWords) {
            if (matched < targetWords.size && spoken == targetWords[matched]) {
                matched++
            }
        }
        return matched == targetWords.size
    }
}
