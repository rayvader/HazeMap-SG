package io.github.rayvader.hazemap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Receives Android's answer to an update install. It is not exported: only Android, answering the
 * app's own install request, can send to it.
 */
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.onInstallStatus(
            context,
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        )
    }
}
