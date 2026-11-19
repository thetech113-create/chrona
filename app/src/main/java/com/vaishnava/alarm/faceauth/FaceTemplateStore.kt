package com.vaishnava.alarm.faceauth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

object FaceTemplateStore {
    private const val TAG = "FaceTemplateStore"
    private const val KEY_ALIAS = "FaceAuthKeyAlias"
    private const val PREFS_NAME = "face_auth_prefs"
    private const val KEY_ENCRYPTED_TEMPLATE = "encrypted_face_template"
    private const val KEY_IV = "face_template_iv"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val EMBEDDING_SIZE = 192

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getStorageContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
    }

    fun isEnrolled(context: Context): Boolean {
        val dpsContext = getStorageContext(context)
        val prefs = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_ENCRYPTED_TEMPLATE) && prefs.contains(KEY_IV)
    }

    fun saveEnrolledTemplate(context: Context, samples: List<FloatArray>): Boolean {
        if (samples.isEmpty()) return false

        return try {
            // Average multiple face embedding samples
            val meanEmbedding = FloatArray(EMBEDDING_SIZE)
            for (sample in samples) {
                for (i in 0 until EMBEDDING_SIZE.coerceAtMost(sample.size)) {
                    meanEmbedding[i] += sample[i]
                }
            }
            for (i in meanEmbedding.indices) {
                meanEmbedding[i] /= samples.size.toFloat()
            }

            // L2 normalize mean vector
            var normSum = 0.0f
            for (v in meanEmbedding) {
                normSum += v * v
            }
            val norm = sqrt(normSum.toDouble()).toFloat().coerceAtLeast(1e-6f)
            for (i in meanEmbedding.indices) {
                meanEmbedding[i] /= norm
            }

            // Serialize embedding to JSON
            val json = Gson().toJson(meanEmbedding)
            val secretKey = getSecretKey()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

            val encodedIv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encodedEncrypted = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            val dpsContext = getStorageContext(context)
            dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENCRYPTED_TEMPLATE, encodedEncrypted)
                .putString(KEY_IV, encodedIv)
                .commit()

            Log.d(TAG, "Successfully saved Keystore-encrypted face template from ${samples.size} samples")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving enrolled face template: ${e.message}", e)
            false
        }
    }

    fun getEnrolledEmbedding(context: Context): FloatArray? {
        val dpsContext = getStorageContext(context)
        val prefs = dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val encodedEncrypted = prefs.getString(KEY_ENCRYPTED_TEMPLATE, null) ?: return null
        val encodedIv = prefs.getString(KEY_IV, null) ?: return null

        return try {
            val encryptedBytes = Base64.decode(encodedEncrypted, Base64.NO_WRAP)
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)

            val secretKey = getSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            val json = String(decryptedBytes, Charsets.UTF_8)

            Gson().fromJson(json, FloatArray::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting stored face template: ${e.message}", e)
            null
        }
    }

    fun clearEnrolledTemplate(context: Context) {
        try {
            val dpsContext = getStorageContext(context)
            dpsContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(KEY_ENCRYPTED_TEMPLATE)
                .remove(KEY_IV)
                .commit()

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            Log.d(TAG, "Cleared enrolled face template")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing face template: ${e.message}", e)
        }
    }

    fun aggregateEmbeddings(samples: List<FloatArray>): FloatArray? {
        if (samples.isEmpty()) return null

        return try {
            val meanEmbedding = FloatArray(EMBEDDING_SIZE)
            for (sample in samples) {
                for (i in 0 until EMBEDDING_SIZE.coerceAtMost(sample.size)) {
                    meanEmbedding[i] += sample[i]
                }
            }
            for (i in meanEmbedding.indices) {
                meanEmbedding[i] /= samples.size.toFloat()
            }

            var normSum = 0.0f
            for (v in meanEmbedding) {
                normSum += v * v
            }
            val norm = sqrt(normSum.toDouble()).toFloat().coerceAtLeast(1e-6f)
            for (i in meanEmbedding.indices) {
                meanEmbedding[i] /= norm
            }

            meanEmbedding
        } catch (e: Exception) {
            Log.e(TAG, "Error aggregating embeddings: ${e.message}", e)
            null
        }
    }
}
