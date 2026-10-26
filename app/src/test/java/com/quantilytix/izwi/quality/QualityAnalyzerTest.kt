package com.quantilytix.izwi.quality

import com.quantilytix.izwi.data.ReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

class QualityAnalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A steady 220 Hz tone well above the silence threshold and well below
     * clipping, so duration is the only thing that can raise a warning. */
    private fun toneWav(seconds: Double, sampleRate: Int = 16000): File {
        val file = tmp.newFile()
        val n = (seconds * sampleRate).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (8000 * sin(2 * PI * 220 * i / sampleRate)).toInt()
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray())
            raf.write(intLE(36 + data.size))
            raf.write("WAVEfmt ".toByteArray())
            raf.write(intLE(16))
            raf.write(byteArrayOf(1, 0, 1, 0)) // PCM, mono
            raf.write(intLE(sampleRate))
            raf.write(intLE(sampleRate * 2))
            raf.write(byteArrayOf(2, 0, 16, 0)) // block align, bits per sample
            raf.write("data".toByteArray())
            raf.write(intLE(data.size))
            raf.write(data)
        }
        return file
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun statusFor(category: String, seconds: Double, sampleRate: Int = 16000): String =
        QualityAnalyzer.analyze(toneWav(seconds, sampleRate), QualityAnalyzer.boundsForCategory(category)).suggestedStatus

    @Test
    fun categoryRouting() {
        assertEquals(QualityAnalyzer.FREE_FORM_BOUNDS, QualityAnalyzer.boundsForCategory("free_form"))
        assertEquals(QualityAnalyzer.FREE_FORM_BOUNDS, QualityAnalyzer.boundsForCategory("free_form_cue_card"))
        assertEquals(QualityAnalyzer.FREE_FORM_BOUNDS, QualityAnalyzer.boundsForCategory("own_words"))
        assertEquals(QualityAnalyzer.LONGFORM_BOUNDS, QualityAnalyzer.boundsForCategory("reports_and_insights"))
        assertEquals(QualityAnalyzer.SHORT_BOUNDS, QualityAnalyzer.boundsForCategory("own_words_extra"))
        assertEquals(QualityAnalyzer.SHORT_BOUNDS, QualityAnalyzer.boundsForCategory("names"))
    }

    @Test
    fun freeFormLongTakesAreNormal() {
        assertEquals(ReviewStatus.ACCEPTED, statusFor("free_form_cue_card", 20.0))
        assertEquals(ReviewStatus.ACCEPTED, statusFor("free_form_cue_card", 60.0))
        assertEquals(ReviewStatus.ACCEPTED, statusFor("own_words", 120.0))
    }

    @Test
    fun freeFormOutsideTargetWarnsInsideHardBounds() {
        assertEquals(ReviewStatus.WARNING, statusFor("free_form_read_back", 10.0))
        assertEquals(ReviewStatus.WARNING, statusFor("own_words", 150.0))
    }

    @Test
    fun freeFormOutsideHardBoundsSuggestsRetake() {
        assertEquals(ReviewStatus.RETAKE, statusFor("free_form_cue_card", 2.0))
        assertEquals(ReviewStatus.RETAKE, statusFor("own_words", 181.0))
    }

    /** The longest take free-form allows, at the recorder's preferred rate:
     * it must load and analyse, and only warn. */
    @Test
    fun fullLengthTakeAt24kHzIsAnalysed() {
        val result = QualityAnalyzer.analyze(toneWav(180.0, 24000), QualityAnalyzer.FREE_FORM_BOUNDS)
        assertEquals(180.0, result.durationSeconds, 0.001)
        assertEquals(ReviewStatus.WARNING, result.suggestedStatus)
        assertTrue(result.warnings.single().startsWith("duration outside 20-120s target"))
    }

    @Test
    fun existingRangesAreUnchanged() {
        assertEquals(ReviewStatus.ACCEPTED, statusFor("reports_and_insights", 20.0))
        assertEquals(ReviewStatus.RETAKE, statusFor("reports_and_insights", 61.0))
        assertEquals(ReviewStatus.ACCEPTED, statusFor("names", 5.0))
        assertEquals(ReviewStatus.WARNING, statusFor("names", 10.0))
        assertEquals(ReviewStatus.RETAKE, statusFor("names", 20.0))
    }
}
