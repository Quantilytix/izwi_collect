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

/** Duration ranges for one kind of take: inside target is normal, outside
 * target only warns, and outside the hard bounds suggests a retake. */
data class DurationBounds(
    val targetMin: Double,
    val targetMax: Double,
    val hardMin: Double,
    val hardMax: Double,
)

/**
 * Lightweight on-device checks from PLAN_B Phase 4: clipping, excessive
 * silence, loudness, duration, and a background-noise proxy. These only
 * warn and recommend a retake — they never delete a recording.
 */
object QualityAnalyzer {

    val SHORT_BOUNDS = DurationBounds(targetMin = 3.0, targetMax = 8.0, hardMin = 1.0, hardMax = 15.0)

    // reports_and_insights prompts are deliberately multi-sentence spoken
    // paragraphs, not short carrier phrases — the ordinary 3-8s/1-15s
    // ranges would flag every one of them as needing a forced retake.
    val LONGFORM_BOUNDS = DurationBounds(targetMin = 10.0, targetMax = 35.0, hardMin = 4.0, hardMax = 60.0)

    // Cue-card and own-words takes are spoken freely for 30 to 120 seconds
    // (PLAN_AUDRY_VOICE.md, free-form speech). Segmentation into training
    // clips happens on the server, so a long take is the normal case here.
    val FREE_FORM_BOUNDS = DurationBounds(targetMin = 20.0, targetMax = 120.0, hardMin = 3.0, hardMax = 180.0)

    /** Free-form categories are any whose name starts with "free_form"
     * (free_form_cue_card, free_form_read_back, ...) plus "own_words". */
    fun boundsForCategory(category: String): DurationBounds = when {
        category.startsWith("free_form") || category == "own_words" -> FREE_FORM_BOUNDS
        category == "reports_and_insights" -> LONGFORM_BOUNDS
        else -> SHORT_BOUNDS
    }

    private const val CLIPPING_SAMPLE_THRESHOLD = 32700
    private const val CLIPPING_FRACTION_LIMIT = 0.001
    private const val SILENCE_RMS_THRESHOLD = 300.0

    fun analyze(wavFile: File, bounds: DurationBounds = SHORT_BOUNDS): QualityResult {
        val targetMin = bounds.targetMin
        val targetMax = bounds.targetMax
        val hardMin = bounds.hardMin
        val hardMax = bounds.hardMax

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
        if (duration < hardMin || duration > hardMax) warnings.add("duration far outside target range")
        else if (duration < targetMin || duration > targetMax) {
            warnings.add("duration outside ${targetMin.toInt()}-${targetMax.toInt()}s target")
        }
        if (speechRatio < 0.25) warnings.add("mostly silence")
        if (snrProxy < 6.0) warnings.add("low signal-to-noise ratio, possible background noise")

        val qualityScore = computeScore(clippingDetected, duration, targetMin, targetMax, speechRatio, snrProxy)

        val suggestedStatus = when {
            duration < hardMin || duration > hardMax -> ReviewStatus.RETAKE
            clippingDetected && speechRatio < 0.15 -> ReviewStatus.RETAKE
            warnings.isEmpty() -> ReviewStatus.ACCEPTED
            else -> ReviewStatus.WARNING
        }

        return QualityResult(duration, qualityScore, snrProxy, speechRatio, clippingDetected, suggestedStatus, warnings)
    }

    private fun computeScore(clipping: Boolean, duration: Double, targetMin: Double, targetMax: Double, speechRatio: Double, snr: Double): Double {
        var score = 1.0
        if (clipping) score -= 0.4
        if (duration < targetMin || duration > targetMax) score -= 0.15
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

    // Reads straight past the 44-byte header instead of copying the data
    // chunk first: a 180s free-form take at 24 kHz is about 8.6 MB, and the
    // extra copy would have held a third full-size buffer on the heap.
    private fun readPcm16(file: File): ShortArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return ShortArray(0)
        val samples = ShortArray((bytes.size - 44) / 2)
        for (i in samples.indices) {
            val lo = bytes[44 + i * 2].toInt() and 0xFF
            val hi = bytes[44 + i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }
}
