package app.zeekrspeaker

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class UsbWavRecorder(
    private val context: Context,
    private val destinationFolder: File,
    private val requestedFileName: String,
    private val onFinished: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    private val stopping = AtomicBoolean(false)

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    /**
     * Start microphone recording.
     */
    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return

        stopping.set(false)

        worker = Thread({
            recordAudio()
        }, "ZEEKR-USB-Recorder").also {
            it.start()
        }
    }

    /**
     * Stop microphone recording.
     */
    fun stop() {
        stopping.set(true)

        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Recording may already have stopped.
        }
    }

    /**
     * Record microphone audio and save it as a WAV file.
     */
    private fun recordAudio() {

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            onError("Unable to initialise the microphone.")
            return
        }

        val bufferSize = maxOf(minBufferSize * 2, 8192)

        val timestamp = System.currentTimeMillis()

        val rawFile = File(
            context.cacheDir,
            "zeekr-recording-$timestamp.pcm"
        )

        val wavFile = File(
            context.cacheDir,
            "zeekr-recording-$timestamp.wav"
        )

        var audioRecord: AudioRecord? = null

        try {

            // Create microphone recorder.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            recorder = audioRecord

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "The microphone could not be opened."
                )
            }

            // Start recording.
            audioRecord.startRecording()

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException(
                    "Microphone recording did not start."
                )
            }

            // Write microphone audio into a temporary PCM file.
            FileOutputStream(rawFile).use { output ->

                val buffer = ByteArray(bufferSize)

                while (!stopping.get()) {

                    val bytesRead = audioRecord.read(
                        buffer,
                        0,
                        buffer.size
                    )

                    if (bytesRead > 0) {

                        if (!stopping.get()) {
                            output.write(buffer, 0, bytesRead)
                        }

                    } else if (bytesRead < 0) {

                        if (!stopping.get()) {
                            throw IllegalStateException(
                                "Microphone error: $bytesRead"
                            )
                        }

                        break
                    }
                }
            }

            // Release microphone.
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }

            audioRecord.release()
            recorder = null
            audioRecord = null

            if (rawFile.length() == 0L) {
                throw IllegalStateException(
                    "No audio was recorded."
                )
            }

            // Convert PCM data into a WAV file.
            createWavFile(rawFile, wavFile)

            // Use the name entered before recording; add a timestamp only if it already exists.
            val cleanedBase = requestedFileName.trim()
                .replace(Regex("(?i)\\.wav$"), "")
                .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                .trim().take(80)
            if (cleanedBase.isBlank()) {
                throw IllegalArgumentException("Enter a valid recording file name.")
            }
            var filename = "$cleanedBase.wav"
            var destinationFile = File(destinationFolder, filename)
            if (destinationFile.exists()) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                var suffix = stamp
                var counter = 2
                filename = "$cleanedBase-$suffix.wav"
                destinationFile = File(destinationFolder, filename)
                while (destinationFile.exists()) {
                    suffix = "$stamp-$counter"
                    filename = "$cleanedBase-$suffix.wav"
                    destinationFile = File(destinationFolder, filename)
                    counter++
                }
            }

            // Save directly to the selected mounted USB volume. This mirrors
            // the vehicle environment, which exposes USB access via /proc/mounts.
            if (!destinationFolder.exists() && !destinationFolder.mkdirs()) {
                throw IllegalStateException("Could not create the recording folder on the USB drive.")
            }
            if (!destinationFolder.isDirectory || !destinationFolder.canWrite()) {
                throw IllegalStateException("The selected USB drive is not writable.")
            }
            FileInputStream(wavFile).use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            // Remove temporary files.
            rawFile.delete()
            wavFile.delete()

            onFinished(filename)

        } catch (e: Exception) {

            rawFile.delete()
            wavFile.delete()

            onError(
                e.message ?: "Recording failed."
            )

        } finally {

            try {
                audioRecord?.stop()
            } catch (_: Exception) {
            }

            try {
                audioRecord?.release()
            } catch (_: Exception) {
            }

            recorder = null
            worker = null
        }
    }

    /**
     * Convert raw PCM audio into a standard WAV file.
     */
    private fun createWavFile(
        rawFile: File,
        wavFile: File
    ) {

        val dataLength = rawFile.length()

        if (dataLength > Int.MAX_VALUE - 36) {
            throw IllegalStateException(
                "Recording is too large to save as a WAV file."
            )
        }

        val dataSize = dataLength.toInt()

        val byteRate =
            SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8

        val blockAlign =
            CHANNELS * BITS_PER_SAMPLE / 8

        FileOutputStream(wavFile).use { output ->

            // RIFF header.
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(output, 36 + dataSize)

            // WAVE header.
            output.write("WAVE".toByteArray(Charsets.US_ASCII))

            // Format section.
            output.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(output, 16)
            writeShortLE(output, 1)
            writeShortLE(output, CHANNELS)
            writeIntLE(output, SAMPLE_RATE)
            writeIntLE(output, byteRate)
            writeShortLE(output, blockAlign)
            writeShortLE(output, BITS_PER_SAMPLE)

            // Audio data section.
            output.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(output, dataSize)

            // Copy recorded PCM data.
            FileInputStream(rawFile).use { input ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Write a 32-bit integer in little-endian format.
     */
    private fun writeIntLE(
        output: FileOutputStream,
        value: Int
    ) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    /**
     * Write a 16-bit integer in little-endian format.
     */
    private fun writeShortLE(
        output: FileOutputStream,
        value: Int
    ) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }
}