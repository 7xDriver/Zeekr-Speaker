package app.zeekrspeaker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Open this once. It asks for the microphone (and notifications on Android 13+),
 * then sends you to the "display over other apps" screen for the floating button.
 * It closes itself when everything is granted.
 */
class PermissionActivity : ComponentActivity() {

    private var askedOverlay = false
    private var leftForSettings = false

    private val askRuntime =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkMicThenOverlay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) checkMicThenOverlay() else askRuntime.launch(needed.toTypedArray())
    }

    private fun checkMicThenOverlay() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (!micOk) {
            toast("Microphone permission is required for live voice")
            finish()
            return
        }
        checkOverlayThenFinish()
    }

    private fun checkOverlayThenFinish() {
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatService::class.java))
            toast("Floating button started")
            finish()
            return
        }
        if (askedOverlay) {
            toast("Overlay permission is still missing, so the floating button can't show")
            finish()
            return
        }
        askedOverlay = true
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: ActivityNotFoundException) {
            // Some head units have no overlay settings page.
            toast("This head unit has no overlay settings page")
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (askedOverlay) leftForSettings = true
    }

    override fun onResume() {
        super.onResume()
        if (leftForSettings) {
            leftForSettings = false
            checkOverlayThenFinish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
