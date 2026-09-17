package com.quantilytix.izwi.quality

import com.quantilytix.izwi.data.ReviewStatus
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

data class QualityResult(
    val durationSeconds: Double,
    val qualityScore: Double,
    val snrProxy: Double,
    val speechRatio: Double,
    val clippingDetected: Boolean,
    val suggestedStatus: String,
    val warnings: List<String>,
)

/**
 * Lightweight on-device checks from PLAN_B Phase 4: clipping, excessive
 * silence, loudness, duration, and a background-noise proxy. These only
 * warn and recommend a retake — they never delete a recording.
 */
object QualityAnalyzer {

    private const val TARGET_MIN_DURATION = 3.0
    private const val TARGET_MAX_DURATION = 8.0
    private const val HARD_MIN_DURATION = 1.0
    private const val HARD_MAX_DURATION = 15.0
    private const val CLIPPING_SAMPLE_THRESHOLD = 32700
    private const val CLIPPING_FRACTION_LIMIT = 0.001
    private const val SILENCE_RMS_THRESHOLD = 300.0

    fun analyze(wavFile: File): QualityResult {
        val samples = readPcm16(wavFile)
        val sampleRate = readSampleRate(wavFile)
        val duration = if (sampleRate > 0) samples.size.toDouble() / sampleRate else 0.0

        val frameSize = max(1, sampleRate / 50) // 20ms frames
        val frameRmsList = mutableListOf<Double>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + frameSize, samples.size)
            var sum = 0.0
            for (j in i until end) sum += (samples[j] * samples[j]).toDouble()
            frameRmsList.add(sqrt(sum / (end - i)))
            i = end
        }

        val speechFrames = frameRmsList.filter { it > SILENCE_RMS_THRESHOLD }
        val silenceFrames = frameRmsList.filter { it <= SILENCE_RMS_THRESHOLD }
        val speechRatio = if (frameRmsList.isNotEmpty()) speechFrames.size.toDouble() / frameRmsList.size else 0.0

        val speechRms = if (speechFrames.isNotEmpty()) speechFrames.average() else 0.0
        val noiseRms = if (silenceFrames.isNotEmpty()) silenceFrames.average() else 1.0
        val snrProxy = if (noiseRms > 0) 20 * log10(max(speechRms, 1.0) / max(noiseRms, 1.0)) else 0.0

        val clippedCount = samples.count { Math.abs(it.toInt()) >= CLIPPING_SAMPLE_THRESHOLD }
        val clippingDetected = samples.isNotEmpty() && clippedCount.toDouble() / samples.size > CLIPPING_FRACTION_LIMIT

        val warnings = mutableListOf<String>()
        if (clippingDetected) warnings.add("clipping detected")
        if (duration < HARD_MIN_DURATION || duration > HARD_MAX_DURATION) warnings.add("duration far outside target range")
        else if (duration < TARGET_MIN_DURATION || duration > TARGET_MAX_DURATION) warnings.add("duration outside 3-8s target")
        if (speechRatio < 0.25) warnings.add("mostly silence")
        if (snrProxy < 6.0) warnings.add("low signal-to-noise ratio, possible background noise")

        val qualityScore = computeScore(clippingDetected, duration, speechRatio, snrProxy)

        val suggestedStatus = when {
            duration < HARD_MIN_DURATION || duration > HARD_MAX_DURATION -> ReviewStatus.RETAKE
            clippingDetected && speechRatio < 0.15 -> ReviewStatus.RETAKE
            warnings.isEmpty() -> ReviewStatus.ACCEPTED
            else -> ReviewStatus.WARNING
        }

        return QualityResult(duration, qualityScore, snrProxy, speechRatio, clippingDetected, suggestedStatus, warnings)
    }

    private fun computeScore(clipping: Boolean, duration: Double, speechRatio: Double, snr: Double): Double {
        var score = 1.0
        if (clipping) score -= 0.4
        if (duration < TARGET_MIN_DURATION || duration > TARGET_MAX_DURATION) score -= 0.15
        if (speechRatio < 0.25) score -= 0.25
        if (snr < 6.0) score -= 0.2
        return score.coerceIn(0.0, 1.0)
    }

    private fun readSampleRate(file: File): Int {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 44) return 0
            raf.seek(24)
            val bytes = ByteArray(4)
            raf.readFully(bytes)
            return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
        }
    }

    private fun readPcm16(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return ShortArray(0)
        val dataBytes = bytes.copyOfRange(44, bytes.size)
        val samples = ShortArray(dataBytes.size / 2)
        for (i in samples.indices) {
            val lo = dataBytes[i * 2].toInt() and 0xFF
            val hi = dataBytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }
}
