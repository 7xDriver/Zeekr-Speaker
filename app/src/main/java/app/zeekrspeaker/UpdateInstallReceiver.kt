package app.zeekrspeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    legacyConfirmation(intent)
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirmation) } catch (_: Exception) {
                        saveStatus(context, "Android requires installation confirmation. Return to App Updates and retry installation.")
                    }
                } else {
                    saveStatus(context, "Android requires installation confirmation. Return to App Updates and retry installation.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                GitHubUpdater.clearPending(context)
                saveStatus(context, "Update installed successfully. Android may close the app during replacement; reopen ZEEKR Speaker from App Lab / Z-emulator.")
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                saveStatus(context, "Android could not complete installation${if (message.isNotBlank()) ": $message" else "."} The downloaded APK has been kept; tap Install Update.")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConfirmation(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT)

    private fun saveStatus(context: Context, message: String) {
        context.getSharedPreferences("update_state", Context.MODE_PRIVATE).edit()
            .putString("last_status", message).apply()
    }

    companion object { const val ACTION_INSTALL_STATUS = "app.zeekrspeaker.UPDATE_INSTALL_STATUS" }
}
