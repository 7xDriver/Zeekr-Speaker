package app.zeekrspeaker

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Requests transient audio focus with MAY_DUCK while exterior audio is active.
 * The head unit decides which other audio streams/zones, if any, are ducked.
 */
object AudioDucking {
    private const val TAG = "AudioDucking"

    fun acquire(am: AudioManager, reason: String): AudioFocusRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        val request = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                Log.i(TAG, "$reason focus change: $change")
            }
            .build()

        return try {
            val result = am.requestAudioFocus(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "$reason: transient MAY_DUCK focus granted")
                request
            } else {
                Log.w(TAG, "$reason: transient MAY_DUCK focus not granted (result=$result)")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "$reason: audio focus request failed", e)
            null
        }
    }

    fun release(am: AudioManager, request: AudioFocusRequest?) {
        if (request == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            am.abandonAudioFocusRequest(request)
            Log.i(TAG, "transient audio focus abandoned")
        } catch (e: Exception) {
            Log.w(TAG, "could not abandon audio focus", e)
        }
    }
}
