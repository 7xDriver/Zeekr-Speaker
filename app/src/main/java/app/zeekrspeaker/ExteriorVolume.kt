package app.zeekrspeaker

import android.media.AudioManager
import android.util.Log

/**
 * Raises the media volume to maximum while something is playing through the exterior
 * speaker, and puts it back afterwards. Shared by live talking and the sound buttons,
 * so overlapping uses don't restore the volume too early.
 *
 * WARNING: if the exterior bus shares the car's media volume, cabin music will also jump
 * to maximum while this is active. Set [ENABLED] to false to switch it off.
 */
object ExteriorVolume {

    private const val ENABLED = true
    private const val TAG = "ExteriorVolume"

    private var holders = 0
    private var saved = -1

    @Synchronized
    fun acquire(am: AudioManager) {
        if (!ENABLED) return
        holders++
        if (holders > 1) return
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (now != max) {
                saved = now
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not raise volume", e)
        }
    }

    @Synchronized
    fun release(am: AudioManager) {
        if (!ENABLED || holders == 0) return
        holders--
        if (holders > 0) return
        if (saved >= 0) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
            } catch (e: Exception) {
                Log.w(TAG, "could not restore volume", e)
            }
            saved = -1
        }
    }
}
