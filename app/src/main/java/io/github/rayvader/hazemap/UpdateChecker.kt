package io.github.rayvader.hazemap

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * Looks for a newer release on GitHub when the app opens, at most every six hours, and remembers the
 * answer so the banner can show again without a request. The line at the end of the Now tab can ask
 * at once instead ([checkNow]). Only the app's own version number is read from the phone; nothing
 * about the user is sent.
 */
class UpdateChecker(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    fun installedVersion(): String? =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }

    /** The newest release found by the last successful check. */
    fun cached(): UpdateInfo? {
        val version = prefs.getString(Prefs.KEY_UPDATE_VERSION, null) ?: return null
        val url = prefs.getString(Prefs.KEY_UPDATE_URL, null) ?: return null
        val notes = prefs.getString(Prefs.KEY_UPDATE_NOTES, null)?.lines()?.filter { it.isNotBlank() }.orEmpty()
        return UpdateInfo(version, url, notes)
    }

    fun dismissedVersion(): String? = prefs.getString(Prefs.KEY_UPDATE_DISMISSED, null)

    fun dismiss(version: String) {
        prefs.edit().putString(Prefs.KEY_UPDATE_DISMISSED, version).apply()
    }

    /** Asks GitHub when a check is due (or [force]d) and returns the newest release known; null if none. */
    suspend fun refreshIfDue(force: Boolean): UpdateInfo? {
        val now = System.currentTimeMillis()
        if (!force && !UpdateLogic.isDue(prefs.getLong(Prefs.KEY_UPDATE_CHECKED, 0L), now)) return cached()
        return try {
            fetchAndRemember(now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No network or a rate limit: keep what the last check found and try on the next open.
            cached()
        }
    }

    /**
     * Asks GitHub right now, whatever the six-hour wait, for the line at the end of the Now tab, and says
     * what came back. Unlike [refreshIfDue] it does not hide a failure behind the last answer. A banner
     * the person closed earlier comes back once GitHub has answered, as they asked.
     */
    suspend fun checkNow(): CheckResult =
        try {
            val info = fetchAndRemember(System.currentTimeMillis())
            prefs.edit().remove(Prefs.KEY_UPDATE_DISMISSED).apply()
            CheckResult.Reached(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult.Unreachable
        }

    /** Reads GitHub's newest release and remembers it (or that there is none). Throws when GitHub cannot be reached. */
    private suspend fun fetchAndRemember(now: Long): UpdateInfo? {
        val info = UpdateLogic.parseLatest(Http.getText(LATEST_URL, limited = false))
        prefs.edit().apply {
            putLong(Prefs.KEY_UPDATE_CHECKED, now)
            if (info != null) {
                putString(Prefs.KEY_UPDATE_VERSION, info.version)
                putString(Prefs.KEY_UPDATE_URL, info.downloadUrl)
                putString(Prefs.KEY_UPDATE_NOTES, info.notes.joinToString("\n"))
            } else {
                remove(Prefs.KEY_UPDATE_VERSION)
                remove(Prefs.KEY_UPDATE_URL)
                remove(Prefs.KEY_UPDATE_NOTES)
            }
        }.apply()
        return info
    }

    private companion object {
        const val LATEST_URL = "https://api.github.com/repos/rayvader/HazeMap-SG/releases/latest"
    }
}
