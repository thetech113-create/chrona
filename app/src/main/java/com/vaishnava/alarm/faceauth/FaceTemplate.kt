package com.vaishnava.alarm.faceauth

/**
 * A stored face. Two formats share the same FloatArray field (Alarm.enrolledFaceEmbedding and the mission face), so
 * no storage or call site had to change:
 *  - legacy (before 1.0.6): one 192-value embedding made from an unaligned, squashed face crop.
 *  - aligned gallery (1.0.6+): [MARKER, VERSION, n, n embeddings of 192 values], made from landmark-aligned faces in
 *    several head poses. A face is compared with every one and the best score counts.
 * An L2-normalised embedding never holds a value below -1, so the marker cannot be mistaken for one.
 */
object FaceTemplate {
    const val EMBEDDING_SIZE = 192
    private const val MARKER = -1000f
    private const val VERSION = 2f
    private const val HEADER = 3

    fun isLegacy(template: FloatArray): Boolean = !(template.size > HEADER && template[0] == MARKER)

    fun encode(gallery: List<FloatArray>): FloatArray {
        require(gallery.isNotEmpty() && gallery.all { it.size == EMBEDDING_SIZE }) { "gallery of 192-value embeddings" }
        val out = FloatArray(HEADER + gallery.size * EMBEDDING_SIZE)
        out[0] = MARKER
        out[1] = VERSION
        out[2] = gallery.size.toFloat()
        gallery.forEachIndexed { i, emb -> emb.copyInto(out, HEADER + i * EMBEDDING_SIZE) }
        return out
    }

    /** The aligned embeddings of a gallery template; empty for a legacy or damaged one. */
    fun gallery(template: FloatArray): List<FloatArray> {
        if (isLegacy(template)) return emptyList()
        val n = template[2].toInt()
        if (n <= 0 || template.size != HEADER + n * EMBEDDING_SIZE) return emptyList()
        return List(n) { i -> template.copyOfRange(HEADER + i * EMBEDDING_SIZE, HEADER + (i + 1) * EMBEDDING_SIZE) }
    }

    /** Best cosine similarity of [probe] against any embedding of the gallery. */
    fun bestScore(gallery: List<FloatArray>, probe: FloatArray): Float =
        gallery.maxOfOrNull { FaceMatcher.calculateCosineSimilarity(it, probe) } ?: 0f
}
