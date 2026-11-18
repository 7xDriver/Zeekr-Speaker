package app.zeekrspeaker

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Captures eligible head-unit playback and forwards it to the ZEEKR exterior speaker. */
object CarMusicRouter {
    private const val TAG = "CarMusicRouter"
    private const val EXTERIOR_ADDRESS = "BUS12_OUTER_NOTIFY"
    private const val SAMPLE_RATE = 48_000
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_STEREO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO

    private val stopping = AtomicBoolean(true)
    private val presetPlaybackDepth = AtomicInteger(0)
    private val outputLock = Any()
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var callback: ((Boolean, String) -> Unit)? = null

    @Synchronized
    fun start(
        context: Context,
        resultCode: Int,
        data: Intent,
        onState: (Boolean, String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < 29) {
            onState(false, "Car audio capture requires Android 10 or later.")
            return
        }
        stopLocked()
        callback = onState
        val app = context.applicationContext
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val output = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.address?.uppercase()?.contains(EXTERIOR_ADDRESS) == true
            }
            if (output == null) {
                onState(false, "Exterior speaker output not found on this head unit.")
                return
            }
            val manager = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Android did not provide an audio-capture session.")
            projection = mp
            stopping.set(false)
            presetPlaybackDepth.set(0)
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopFromProjection()
                }
            }, android.os.Handler(app.mainLooper))

            val captureConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                // Never capture ExteriorVoice's own preset AudioTrack. This prevents
                // the preset from feeding back into the car-music stream.
                .excludeUid(app.applicationInfo.uid)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_IN)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_IN, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) throw IllegalStateException("Could not initialise playback capture (buffer=$minBuffer).")
            val bufferSize = maxOf(minBuffer * 2, 16 * 1024)
            val input = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
            if (input.state != AudioRecord.STATE_INITIALIZED) {
                input.release()
                throw IllegalStateException("Android could not initialise head-unit audio capture.")
            }

            val outputFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_OUT)
                .build()
            val outputTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(outputFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
            outputTrack.setPreferredDevice(output)
            if (outputTrack.state != AudioTrack.STATE_INITIALIZED) {
                input.release()
                outputTrack.release()
                throw IllegalStateException("Could not initialise exterior speaker output.")
            }

            recorder = input
            track = outputTrack
            input.startRecording()
            outputTrack.play()
            onState(true, "Streaming eligible head-unit audio to the exterior speaker.")
            worker = Thread({ pump() }, "ZEEKR-Car-Music-Router").also { it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start car music routing", e)
            stopLocked()
            onState(false, e.message ?: "Unable to start car music routing.")
        }
    }

    private fun pump() {
        val input = recorder ?: return
        val output = track ?: return
        val buffer = ByteArray(16 * 1024)
        try {
            while (!stopping.get()) {
                val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count > 0 && !stopping.get()) {
                    var offset = 0
                    while (offset < count && !stopping.get()) {
                        synchronized(outputLock) {
                            // Keep draining AudioRecord while a preset plays, but do not
                            // send captured audio to the exterior speaker at that time.
                            if (presetPlaybackDepth.get() == 0 && !stopping.get()) {
                                val written = output.write(
                                    buffer, offset, count - offset,
                                    AudioTrack.WRITE_BLOCKING
                                )
                                if (written < 0) {
                                    throw IllegalStateException("Exterior speaker write failed: $written")
                                }
                                offset += written
                            } else {
                                // Drop this captured chunk; the preset owns the speaker.
                                offset = count
                            }
                        }
                    }
                } else if (count < 0 && !stopping.get()) {
                    throw IllegalStateException("Head-unit playback capture failed: $count")
                }
            }
        } catch (e: Exception) {
            if (!stopping.get()) {
                Log.e(TAG, "Car music stream stopped", e)
                callback?.invoke(false, e.message ?: "Car music stream stopped.")
            }
        } finally {
            stop()
        }
    }


    /** Temporarily gives the exterior speaker to SoundPlayer without ending MediaProjection. */
    fun beginPresetPlayback() {
        synchronized(outputLock) {
            if (stopping.get()) return
            val depth = presetPlaybackDepth.incrementAndGet()
            if (depth == 1) {
                try { track?.pause() } catch (e: Exception) {
                    Log.w(TAG, "Could not pause car music for preset", e)
                }
            }
        }
    }

    /** Returns the speaker to car music after the last overlapping preset finishes. */
    fun endPresetPlayback() {
        synchronized(outputLock) {
            val depth = presetPlaybackDepth.updateAndGet { current ->
                if (current > 0) current - 1 else 0
            }
            if (depth == 0 && !stopping.get()) {
                try { track?.play() } catch (e: Exception) {
                    Log.w(TAG, "Could not resume car music after preset", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopFromProjection() {
        stopLocked()
    }

    @Synchronized
    private fun stopLocked() {
        val wasRunning = !stopping.getAndSet(true)
        try { recorder?.stop() } catch (_: Exception) {}
        try { track?.pause(); track?.flush(); track?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        recorder = null
        track = null
        presetPlaybackDepth.set(0)
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker = null
        if (wasRunning) callback?.invoke(false, "Car music routing stopped.")
    }
}
