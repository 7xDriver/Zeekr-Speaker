package app.zeekrspeaker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Live push-to-talk: cabin microphone -> exterior speaker.
 */
class LiveVoiceService : Service() {

    companion object {
        const val ACTION_ARM = "app.zeekrspeaker.ARM"
        const val MAX_SESSION_MS = 60_000L

        private const val TAG = "LiveVoice"
        private const val SAMPLE_RATE = 44100
        private const val CHUNK = 256
        private const val CHANNEL_ID = "live_voice"
        private const val NOTIF_ID = 1

        private const val EXTERIOR_ADDRESS = "BUS12_OUTER_NOTIFY"
        private const val OUTPUT_USAGE = AudioAttributes.USAGE_MEDIA

        private const val DEFAULT_GAIN = 4f
        private const val MIN_GAIN = 0.5f
        private const val MAX_GAIN = 5f

        @Volatile
        private var microphoneGain = DEFAULT_GAIN

        @Volatile
        var armed = false

        @Volatile
        var holdActive = false

        @Volatile
        var talkingListener: ((Boolean) -> Unit)? = null

        @Volatile
        var sessionLimitMs = MAX_SESSION_MS

        @Volatile
        private var outputVolumePercent = 100

        private val pressSignal = Semaphore(0)

        fun setVolumePercent(percentage: Int) {
            outputVolumePercent = percentage.coerceIn(0, 100)
        }

        fun setMicrophoneGain(gain: Float) {
            microphoneGain = gain.coerceIn(MIN_GAIN, MAX_GAIN)
        }

        fun getMicrophoneGain(): Float {
            return microphoneGain
        }

        fun setHeld(held: Boolean, limitMs: Long = MAX_SESSION_MS) {
            if (held) sessionLimitMs = limitMs
            holdActive = held
            if (held) pressSignal.release()
        }
    }

    @Volatile
    private var engineRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        startAsForeground()

        outputVolumePercent = Prefs.volumePercent(this)

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            saveResult("FAILED: microphone permission is missing")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!engineRunning) {
            engineRunning = true
            Thread(
                { engineLoop() },
                "live-voice"
            ).start()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun engineLoop() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val outDevice = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.address?.uppercase()?.contains(EXTERIOR_ADDRESS) == true
            }

        if (outDevice == null) {
            saveResult(
                "FAILED: exterior output '$EXTERIOR_ADDRESS' not found on this head unit"
            )
            shutdown()
            return
        }

        val minIn = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val minOut = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        var recorder: AudioRecord? = null
        var track: AudioTrack? = null

        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minIn * 2
            )

            recorder = rec

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "the microphone could not be opened"
                )
            }

            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(OUTPUT_USAGE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(minOut * 2)
                .build()

            track = trk

            if (!trk.setPreferredDevice(outDevice)) {
                Log.w(
                    TAG,
                    "setPreferredDevice returned false for '${outDevice.address}'"
                )
            }

            armed = true

            saveResult(
                "Ready. Output: ${outDevice.address}. Hold the floating button to talk."
            )

            while (engineRunning) {
                pressSignal.tryAcquire(500, TimeUnit.MILLISECONDS)

                if (!engineRunning) break

                if (holdActive) {
                    talk(am, rec, trk)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "engine failed", e)
            saveResult("ERROR: $e")

        } finally {
            armed = false

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            try {
                track?.release()
            } catch (_: Exception) {
            }

            shutdown()
        }
    }

    /**
     * One talking session.
     * Volume and microphone gain are applied dynamically.
     */
    private fun talk(
        am: AudioManager,
        recorder: AudioRecord,
        track: AudioTrack
    ) {
        pressSignal.drainPermits()

        talkingListener?.invoke(true)
        val duckFocus = if (Prefs.duckMicrophone(this)) {
            AudioDucking.acquire(am, "Microphone")
        } else {
            Log.i(TAG, "Microphone cabin audio muting disabled by user setting")
            null
        }
        ExteriorVolume.acquire(am)

        val buf = ShortArray(CHUNK)

        var error: String? = null
        var capped = false
        var gainUsed = microphoneGain

        val endAt = SystemClock.elapsedRealtime() + sessionLimitMs

        try {
            if (!VehicleSpeed.isPlaybackAllowed(this)) {
                error = speedBlockMessage()
                holdActive = false
                return
            }

            recorder.startRecording()
            track.play()

            while (engineRunning && holdActive) {
                if (!VehicleSpeed.isPlaybackAllowed(this)) {
                    error = speedBlockMessage()
                    holdActive = false
                    break
                }
                if (SystemClock.elapsedRealtime() >= endAt) {
                    capped = true
                    break
                }

                val n = recorder.read(buf, 0, buf.size)

                if (n > 0) {
                    val volume =
                        outputVolumePercent.coerceIn(0, 100) / 100.0

                    // Read the current gain each chunk so it can be
                    // adjusted while the microphone is active.
                    gainUsed = microphoneGain

                    for (i in 0 until n) {
                        val s = buf[i].toInt()

                        // Microphone gain and soft limiter.
                        val limited =
                            Math.tanh(s / 32768.0 * gainUsed) * 0.94

                        // Apply the user-selected output volume.
                        val y = limited * volume

                        buf[i] = Math.round(y * 32767)
                            .toInt()
                            .coerceIn(-32768, 32767)
                            .toShort()
                    }

                    track.write(buf, 0, n)

                } else if (n < 0) {
                    error = "microphone read error $n"
                    break
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "talk session failed", e)
            error = e.toString()

        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }

            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
            }

            ExteriorVolume.release(am)
            AudioDucking.release(am, duckFocus)

            if (capped || error != null) {
                holdActive = false
            }

            talkingListener?.invoke(false)

            saveResult(
                (if (error == null) {
                    if (capped) {
                        "Stopped at the time limit."
                    } else {
                        "Finished."
                    }
                } else {
                    "ERROR: $error."
                }) +
                        " Gain x$gainUsed | volume ${outputVolumePercent}%"
            )
        }
    }

    private fun speedBlockMessage(): String {
        val speed = VehicleSpeed.currentSpeedKmh(this)
        return if (speed == null) {
            "Playback blocked: vehicle speed unavailable or stale."
        } else {
            "Playback stopped: vehicle speed ${"%.1f".format(java.util.Locale.US, speed)} km/h exceeds the selected limit."
        }
    }

    private fun shutdown() {
        engineRunning = false
        armed = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveResult(msg: String) {
        val time = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.US
        ).format(java.util.Date())

        getSharedPreferences("status", Context.MODE_PRIVATE)
            .edit()
            .putString("last_result", "$time  $msg")
            .apply()

        Log.i(TAG, msg)
    }

    private fun startAsForeground() {
        val nm = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Live voice",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notif: Notification = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Exterior speaker ready")
            .setContentText("Hold the floating button to talk")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        engineRunning = false
        armed = false
        holdActive = false
        pressSignal.release()
        super.onDestroy()
    }
}