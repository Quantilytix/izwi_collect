package com.quantilytix.izwi.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/**
 * Mono 16-bit PCM WAV recorder. Tries 24 kHz first to match the target TTS
 * pipeline; falls back to 16 kHz if the device cannot open that rate
 * reliably, per PLAN_B's recording-format note.
 */
class WavRecorder {

    var sampleRate: Int = 0
        private set

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private var outputFile: File? = null

    var onLevel: ((Float) -> Unit)? = null

    companion object {
        private val PREFERRED_RATES = intArrayOf(24000, 16000)
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @SuppressLint("MissingPermission")
    fun start(outFile: File) {
        outputFile = outFile
        val (record, rate) = openBestAudioRecord()
        audioRecord = record
        sampleRate = rate
        isRecording = true

        val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
        val bufferSize = if (minBuf > 0) minBuf else 4096

        record.startRecording()
        recordingThread = Thread {
            writeWavBody(record, bufferSize, outFile)
        }.also { it.start() }
    }

    fun stop(): File? {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null
        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // was never successfully started; nothing to stop.
            }
            it.release()
        }
        audioRecord = null
        outputFile?.let { patchWavHeader(it, sampleRate) }
        return outputFile
    }

    fun cancel() {
        isRecording = false
        recordingThread?.join(2000)
        recordingThread = null
        audioRecord?.release()
        audioRecord = null
        outputFile?.delete()
        outputFile = null
    }

    @SuppressLint("MissingPermission")
    private fun openBestAudioRecord(): Pair<AudioRecord, Int> {
        for (rate in PREFERRED_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL, ENCODING)
            if (minBuf <= 0) continue
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                CHANNEL,
                ENCODING,
                minBuf * 2,
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return record to rate
            }
            record.release()
        }
        error("No usable audio input rate found on this device")
    }

    private fun writeWavBody(record: AudioRecord, bufferSize: Int, outFile: File) {
        outFile.outputStream().use { out ->
            // 44-byte placeholder header, patched with real sizes on stop().
            out.write(ByteArray(44))
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val bytes = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                    onLevel?.invoke(rmsLevel(buffer, read))
                }
            }
        }
    }

    private fun rmsLevel(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += (buffer[i] * buffer[i]).toDouble()
        val rms = Math.sqrt(sum / count)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun patchWavHeader(file: File, sampleRate: Int) {
        val dataSize = file.length() - 44
        if (dataSize < 0) return
        RandomAccessFile(file, "rw").use { raf ->
            val byteRate = sampleRate * 1 * 16 / 8
            val blockAlign = 1 * 16 / 8

            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.write(intLE((36 + dataSize).toInt()))
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.write(intLE(16))
            raf.write(shortLE(1)) // PCM
            raf.write(shortLE(1)) // mono
            raf.write(intLE(sampleRate))
            raf.write(intLE(byteRate))
            raf.write(shortLE(blockAlign))
            raf.write(shortLE(16)) // bits per sample
            raf.write("data".toByteArray())
            raf.write(intLE(dataSize.toInt()))
        }
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}
