package com.vaishnava.alarm.faceauth

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class FaceEmbeddingExtractor(private val context: Context) {
    companion object {
        private const val TAG = "FaceEmbeddingExtractor"
        private const val MODEL_FILE = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 192
    }

    private var interpreter: Interpreter? = null
    var isModelLoaded = false
        private set

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val fileDescriptor = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true
            Log.d(TAG, "MobileFaceNet model successfully loaded from assets/$MODEL_FILE")
        } catch (e: Exception) {
            isModelLoaded = false
            Log.e(
                TAG,
                "Failed to load $MODEL_FILE from assets. Please place mobilefacenet.tflite in app/src/main/assets/: ${e.message}"
            )
        }
    }

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val tflite = interpreter ?: run {
            Log.e(TAG, "Interpreter not initialized or model missing")
            return null
        }

        return try {
            // Resize bitmap to 112x112
            val resizedBitmap = if (faceBitmap.width == INPUT_SIZE && faceBitmap.height == INPUT_SIZE) {
                faceBitmap
            } else {
                Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            }

            // Prepare ByteBuffer for 112x112 RGB float input
            val imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            imgData.order(ByteOrder.nativeOrder())
            imgData.rewind()

            val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
            resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

            // Normalize pixels: (pixel - 127.5) / 128.0
            for (pixelValue in intValues) {
                val r = (pixelValue shr 16 and 0xFF)
                val g = (pixelValue shr 8 and 0xFF)
                val b = (pixelValue and 0xFF)

                imgData.putFloat((r - 127.5f) / 128.0f)
                imgData.putFloat((g - 127.5f) / 128.0f)
                imgData.putFloat((b - 127.5f) / 128.0f)
            }

            val outputEmbeddings = Array(1) { FloatArray(EMBEDDING_SIZE) }
            tflite.run(imgData, outputEmbeddings)

            val rawEmbedding = outputEmbeddings[0]

            // L2 normalize vector
            var sum = 0.0f
            for (v in rawEmbedding) {
                sum += v * v
            }
            val norm = sqrt(sum.toDouble()).toFloat().coerceAtLeast(1e-6f)
            for (i in rawEmbedding.indices) {
                rawEmbedding[i] /= norm
            }

            rawEmbedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting face embedding: ${e.message}", e)
            null
        }
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (_: Exception) {}
    }
}
