package app.zeekrspeaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.nio.ByteOrder

/**
 * Plays the sound buttons through the exterior speaker.
 */
object SoundPlayer {

    private const val TAG = "SoundPlayer"
    private const val EXTERIOR_ADDRESS = "BUS12_OUTER_NOTIFY"
    private const val MAX_SECONDS = 60
    private const val CHUNK = 4096

    private class Decoded(
        val pcm: ShortArray,
        val rate: Int
    )

    @Volatile
    var playingSlot = -1
        private set

    @Volatile
    var listener: ((Int) -> Unit)? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var outputVolumePercent = 100

    private val cache = HashMap<String, Decoded>()
    private var token = 0
    private val main = Handler(Looper.getMainLooper())

    /**
     * Called when the volume slider changes.
     * Updates an active sound immediately.
     */
    fun setVolumePercent(percentage: Int) {
        outputVolumePercent = percentage.coerceIn(0, 100)

        try {
            activeTrack?.setVolume(outputVolumePercent / 100f)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update playback volume", e)
        }
    }

    fun toggle(context: Context, slot: Int) {
        val app = context.applicationContext
        var myToken = 0

        synchronized(this) {
            if (playingSlot == slot) {
                stopLocked()
                return
            }

            stopLocked()
            myToken = ++token
            playingSlot = slot
        }

        listener?.invoke(slot)

        Thread(
            { play(app, slot, myToken) },
            "sound-player"
        ).start()
    }

    fun stop() {
        synchronized(this) {
            stopLocked()
        }
    }

    fun clearCache() {
        synchronized(cache) {
            cache.clear()
        }
    }

    fun preload(context: Context) {
        val app = context.applicationContext

        Thread({
            for (i in 0 until Prefs.SLOT_COUNT) {
                val source = Prefs.slot(app, i).source

                if (source.isNotEmpty()) {
                    try {
                        load(app, source)
                    } catch (e: Exception) {
                        Log.w(TAG, "preload failed", e)
                    }
                }
            }
        }, "sound-preload").start()
    }

    // ---- playback ------------------------------------------------------

    private fun stopLocked() {
        token++

        if (playingSlot != -1) {
            playingSlot = -1
            listener?.invoke(-1)
        }
    }

    private fun isCurrent(myToken: Int) =
        synchronized(this) {
            token == myToken
        }

    private fun finish(myToken: Int) {
        synchronized(this) {
            if (token == myToken && playingSlot != -1) {
                playingSlot = -1
                listener?.invoke(-1)
            }
        }
    }

    private fun play(
        ctx: Context,
        slot: Int,
        myToken: Int
    ) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        var track: AudioTrack? = null
        var volumeRaised = false
        var carMusicPaused = false
        var duckFocus: android.media.AudioFocusRequest? = null
        var speedBlocked = false

        try {
            VehicleSpeed.start(ctx)
            if (!VehicleSpeed.isPlaybackAllowed(ctx)) {
                speedBlocked = true
                say(ctx, speedBlockMessage(ctx))
                return
            }

            val config = Prefs.slot(ctx, slot)

            if (config.source.isEmpty()) {
                say(
                    ctx,
                    "No sound chosen for ${Prefs.displayLabel(ctx, slot)}. Choose one in Settings."
                )
                return
            }

            val sound = load(ctx, config.source)

            val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull {
                    it.address?.uppercase()
                        ?.contains(EXTERIOR_ADDRESS) == true
                }

            if (device == null) {
                say(ctx, "Exterior speaker output not found on this head unit")
                return
            }

            val minBuf = AudioTrack.getMinBufferSize(
                sound.rate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufBytes = maxOf(minBuf, CHUNK * 4)

            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sound.rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build()

            track = t
            activeTrack = t

            t.setPreferredDevice(device)

            // Apply the saved output volume.
            t.setVolume(outputVolumePercent.coerceIn(0, 100) / 100f)

            if (!VehicleSpeed.isPlaybackAllowed(ctx)) {
                speedBlocked = true
                say(ctx, speedBlockMessage(ctx))
                return
            }

            // Pause only the car-music forwarding path. MediaProjection and its
            // permission session remain alive, so presets do not trigger approval again.
            CarMusicRouter.beginPresetPlayback()
            carMusicPaused = true

            // Preset ducking is configurable. Microphone ducking remains enabled
            // independently in LiveVoiceService.
            duckFocus = if (Prefs.duckPresets(ctx)) {
                AudioDucking.acquire(am, "Preset")
            } else {
                Log.i(TAG, "Preset cabin audio muting disabled by user setting")
                null
            }
            ExteriorVolume.acquire(am)
            volumeRaised = true

            t.play()

            var offset = 0

            while (offset < sound.pcm.size && isCurrent(myToken)) {
                if (!VehicleSpeed.isPlaybackAllowed(ctx)) {
                    speedBlocked = true
                    say(ctx, speedBlockMessage(ctx))
                    break
                }
                val count = minOf(CHUNK, sound.pcm.size - offset)
                val written = t.write(sound.pcm, offset, count)

                if (written < 0) break

                offset += written
            }

            val deadline = SystemClock.elapsedRealtime() +
                    (bufBytes / 2) * 1000L / sound.rate + 500

            while (
                isCurrent(myToken) &&
                t.playbackHeadPosition < sound.pcm.size &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                if (!VehicleSpeed.isPlaybackAllowed(ctx)) {
                    speedBlocked = true
                    say(ctx, speedBlockMessage(ctx))
                    break
                }
                Thread.sleep(20)
            }

        } catch (e: Exception) {
            Log.e(TAG, "playback failed", e)
            say(ctx, "Could not play the sound: ${e.message}")

        } finally {
            try {
                track?.pause()
                track?.flush()
            } catch (_: Exception) {
            }

            try {
                track?.release()
            } catch (_: Exception) {
            }

            if (activeTrack === track) {
                activeTrack = null
            }

            if (volumeRaised) {
                ExteriorVolume.release(am)
            }
            AudioDucking.release(am, duckFocus)

            if (carMusicPaused) {
                CarMusicRouter.endPresetPlayback()
            }

            finish(myToken)
        }
    }

    private fun speedBlockMessage(ctx: Context): String {
        val speed = VehicleSpeed.currentSpeedKmh(ctx)
        return if (speed == null) {
            "Playback blocked: vehicle speed unavailable or stale."
        } else {
            val limit = Prefs.maxVehicleSpeed(ctx)
            "Playback stopped: vehicle speed ${"%.1f".format(java.util.Locale.US, speed)} km/h exceeds the ${limit} km/h limit."
        }
    }

    private fun say(ctx: Context, message: String) {
        main.post {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    // ---- loading -------------------------------------------------------

    private fun load(
        ctx: Context,
        source: String
    ): Decoded {

        synchronized(cache) {
            cache[source]?.let {
                return it
            }
        }

        val sound = when {
            source.startsWith("raw:") -> {
                val resourceName = source.removePrefix("raw:")

                val resourceId = ctx.resources.getIdentifier(
                    resourceName,
                    "raw",
                    ctx.packageName
                )

                if (resourceId == 0) {
                    throw IllegalArgumentException(
                        "Bundled sound not found: $resourceName"
                    )
                }

                val resourceUri = Uri.parse(
                    "android.resource://${ctx.packageName}/$resourceId"
                )

                decodeFile(ctx, resourceUri)
            }

            source.startsWith("builtin:") ->
                synth(source.removePrefix("builtin:"))

            source.startsWith("uri:") ->
                decodeFile(ctx, Uri.parse(source.removePrefix("uri:")))

            else ->
                throw IllegalArgumentException("unknown sound source")
        }

        synchronized(cache) {
            if (cache.size >= 8) cache.clear()
            cache[source] = sound
        }

        return sound
    }

    private fun decodeFile(
        ctx: Context,
        uri: Uri
    ): Decoded {

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(ctx, uri, null)

            var trackIndex = -1
            var found: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)

                if ((f.getString(MediaFormat.KEY_MIME) ?: "")
                        .startsWith("audio/")
                ) {
                    trackIndex = i
                    found = f
                    break
                }
            }

            val format = found
                ?: throw IllegalArgumentException("that file has no audio")

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("unknown audio type")

            val c = MediaCodec.createDecoderByType(mime)
            codec = c

            c.configure(format, null, null, 0)
            c.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                .coerceAtLeast(1)

            val maxSamples = rate * MAX_SECONDS
            val out = ShortArray(maxSamples)

            var n = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            val started = SystemClock.elapsedRealtime()

            while (
                !outputDone &&
                n < maxSamples &&
                SystemClock.elapsedRealtime() - started < 30_000
            ) {

                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(10_000)

                    if (inIndex >= 0) {
                        val inBuf = c.getInputBuffer(inIndex)

                        val size = if (inBuf == null) {
                            -1
                        } else {
                            extractor.readSampleData(inBuf, 0)
                        }

                        if (size < 0) {
                            c.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )

                            inputDone = true

                        } else {
                            c.queueInputBuffer(
                                inIndex,
                                0,
                                size,
                                extractor.sampleTime,
                                0
                            )

                            extractor.advance()
                        }
                    }
                }

                val outIndex = c.dequeueOutputBuffer(info, 10_000)

                if (outIndex >= 0) {
                    val outBuf = c.getOutputBuffer(outIndex)

                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)

                        val samples = outBuf
                            .order(ByteOrder.nativeOrder())
                            .asShortBuffer()

                        while (samples.remaining() >= channels && n < maxSamples) {
                            var sum = 0

                            for (ch in 0 until channels) {
                                sum += samples.get().toInt()
                            }

                            out[n++] = (sum / channels).toShort()
                        }
                    }

                    c.releaseOutputBuffer(outIndex, false)

                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat

                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        .coerceAtLeast(1)
                }
            }

            val pcm = out.copyOf(n)

            if (pcm.isEmpty()) {
                throw IllegalArgumentException(
                    "could not read any audio from that file"
                )
            }

            normalize(pcm)

            return Decoded(pcm, rate)

        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }

            try {
                codec?.release()
            } catch (_: Exception) {
            }

            extractor.release()
        }
    }

    /**
     * Normalises quiet sounds and fades the final 20 ms.
     */
    private fun normalize(pcm: ShortArray) {
        var peak = 0

        for (s in pcm) {
            val a = Math.abs(s.toInt())
            if (a > peak) peak = a
        }

        if (peak > 0) {
            var gain = 32767.0 * 0.945 / peak

            if (gain > 16.0) gain = 16.0

            if (gain >= 1.02) {
                for (i in pcm.indices) {
                    pcm[i] = Math.round(pcm[i] * gain)
                        .toInt()
                        .coerceIn(-32768, 32767)
                        .toShort()
                }
            }
        }

        val fade = minOf(pcm.size, 882)

        for (i in 0 until fade) {
            val idx = pcm.size - fade + i

            pcm[idx] = (
                    pcm[idx] * (1.0 - (i + 1.0) / fade)
                    ).toInt().toShort()
        }
    }

    // ---- built-in horns ------------------------------------------------

    private fun synth(id: String): Decoded {
        val rate = 44100

        val wave: FloatArray = when (id) {
            "horn1" ->
                tone(rate, doubleArrayOf(415.3, 523.3), 900, 6, 1.6)

            "horn2" -> {
                val honk = tone(
                    rate,
                    doubleArrayOf(440.0, 554.4),
                    280,
                    6,
                    1.6
                )

                val gap = FloatArray(rate * 110 / 1000)
                honk + gap + honk
            }

            "horn3" ->
                tone(
                    rate,
                    doubleArrayOf(311.1, 370.0, 415.3, 466.2),
                    1700,
                    7,
                    2.0
                )

            else ->
                throw IllegalArgumentException("unknown built-in sound: $id")
        }

        val pcm = ShortArray(wave.size) {
            (wave[it] * 32767f)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        normalize(pcm)

        return Decoded(pcm, rate)
    }

    private fun tone(
        rate: Int,
        freqs: DoubleArray,
        ms: Int,
        harmonics: Int,
        drive: Double
    ): FloatArray {

        val n = rate * ms / 1000
        val out = FloatArray(n)

        val attack = rate * 25 / 1000
        val release = rate * 90 / 1000

        for (i in 0 until n) {
            val t = i / rate.toDouble()
            var v = 0.0

            for (f in freqs) {
                for (h in 1..harmonics) {
                    v += Math.sin(2 * Math.PI * f * h * t) / h
                }
            }

            v = Math.tanh(v * drive / freqs.size)

            var env = 1.0

            if (i < attack) {
                env = i / attack.toDouble()
            }

            if (i > n - release) {
                env = minOf(env, (n - i) / release.toDouble())
            }

            out[i] = (v * env).toFloat()
        }

        return out
    }
}