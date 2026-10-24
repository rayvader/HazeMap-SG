package io.github.rayvader.hazemap

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/** Where the self-update stands. [info] is the release it is about, or null when there is none. */
sealed class UpdateState(val info: UpdateInfo?) {
    object Hidden : UpdateState(null)
    class Available(info: UpdateInfo) : UpdateState(info)
    class Downloading(info: UpdateInfo, val percent: Int?) : UpdateState(info)
    class Ready(info: UpdateInfo, val installOnLeave: Boolean) : UpdateState(info)
    class Installing(info: UpdateInfo) : UpdateState(info)
    class Failed(info: UpdateInfo, val problem: UpdateProblem) : UpdateState(info)
}

/**
 * Keeps the app up to date by itself. When the update check finds a newer release, its APK is
 * downloaded (by itself off mobile data, or when the person taps Update), checked, and installed when
 * the person leaves the app, so the app never closes in front of them. Android 12 and newer can do
 * that with no tap; older Android, or a phone that wants a yes first, shows Android's own prompt.
 * Android installs the file only if it is signed with the same key as the app on the phone.
 */
object Updater {
    private const val TAG = "Updater"
    private const val ACTION_STATUS = "io.github.rayvader.hazemap.UPDATE_STATUS"

    /** Far above the app's size, so a wrong file cannot fill the phone. */
    private const val MAX_APK_BYTES = 100L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val current = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = current
    private var job: Job? = null

    /** How many of the app's activities are started. */
    private var started = 0

    /** True once the person has left the app, until an activity starts again. */
    private var leftApp = false

    /** The activity on screen, to open Android's install prompt from. */
    private var onScreen: WeakReference<Activity>? = null

    /**
     * The newest release worth showing, or null for none. [autoDownload] lets the download start with
     * no tap; the caller allows it only off mobile data.
     */
    fun offer(context: Context, latest: UpdateInfo?, autoDownload: Boolean) {
        val app = context.applicationContext
        if (latest == null) {
            job?.cancel()
            current.value = UpdateState.Hidden
            updateDir(app).deleteRecursively()
            return
        }
        val now = current.value
        val retryDownload = now is UpdateState.Failed && now.problem == UpdateProblem.DOWNLOAD
        // Already working on this release: let it carry on.
        if (now.info == latest && now !is UpdateState.Available && !retryDownload) return
        val failed = UpdateLogic.failedProblem(prefs(app).getString(Prefs.KEY_UPDATE_FAILED, null), latest.version)
        if (failed != null) {
            // A try that failed is not repeated by itself: the banner offers to try again.
            current.value = UpdateState.Failed(latest, failed)
            return
        }
        val file = fileFor(app, latest)
        if (file.isFile) {
            job?.cancel()
            job = scope.launch { checkAndReady(app, latest, file) }
            return
        }
        current.value = UpdateState.Available(latest)
        if (autoDownload && latest.isApk) download(app, latest, auto = true)
    }

    /** Downloads the release's APK, checks it, and gets it ready to install. */
    fun download(context: Context, info: UpdateInfo, auto: Boolean = false) {
        val app = context.applicationContext
        if (!info.isApk) return
        prefs(app).edit().remove(Prefs.KEY_UPDATE_FAILED).apply()
        job?.cancel()
        current.value = UpdateState.Downloading(info, null)
        Log.d(TAG, "update download start: ${info.version} auto=$auto")
        job = scope.launch {
            val file = fileFor(app, info)
            try {
                withContext(Dispatchers.IO) {
                    // Only one download is kept: an older one is no longer needed.
                    updateDir(app).listFiles()?.forEach { if (it != file) it.delete() }
                    updateDir(app).mkdirs()
                }
                var shown = -1
                Http.download(info.downloadUrl, file, MAX_APK_BYTES) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else -1
                    if (percent >= 0 && percent != shown) {
                        shown = percent
                        current.value = UpdateState.Downloading(info, percent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed: ${info.version}", e)
                fail(app, info, UpdateProblem.DOWNLOAD)
                return@launch
            }
            checkAndReady(app, info, file)
        }
    }

    /** The Install button: install now, showing Android's prompt if it wants one. */
    fun installNow(context: Context) {
        val s = current.value
        if (s is UpdateState.Ready) install(context.applicationContext, s.info ?: return, asked = true)
    }

    /** The version an update has just installed, once, so the app can say so; null otherwise. */
    fun justUpdatedTo(context: Context, installed: String?): String? {
        val app = context.applicationContext
        val prefs = prefs(app)
        if (!UpdateLogic.justUpdated(prefs.getString(Prefs.KEY_UPDATE_INSTALLING, null), installed)) return null
        prefs.edit()
            .remove(Prefs.KEY_UPDATE_INSTALLING)
            .remove(Prefs.KEY_UPDATE_ASKED)
            .remove(Prefs.KEY_UPDATE_FAILED)
            .apply()
        updateDir(app).deleteRecursively()
        return installed
    }

    /** Android's answer to an install, delivered through [UpdateStatusReceiver]. */
    fun onInstallStatus(context: Context, status: Int, message: String?, confirm: Intent?) {
        val app = context.applicationContext
        val prefs = prefs(app)
        val version = prefs.getString(Prefs.KEY_UPDATE_INSTALLING, null)
        val info = current.value.info?.takeIf { it.version == version }
            ?: UpdateChecker(app).cached()?.takeIf { it.version == version }
        Log.d(TAG, "update status $status for $version: $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val screen = onScreen?.get()
                if (confirm != null && screen != null) {
                    try {
                        screen.startActivity(confirm)
                    } catch (e: ActivityNotFoundException) {
                        if (info != null) fail(app, info, UpdateProblem.INSTALL)
                    }
                } else {
                    // Nobody is looking, so Android's prompt cannot open: the banner asks for a tap instead.
                    if (version != null) {
                        prefs.edit()
                            .putString(Prefs.KEY_UPDATE_ASKED, UpdateLogic.askRecord(version, canInstallApps(app)))
                            .apply()
                    }
                    if (info != null) current.value = UpdateState.Ready(info, installOnLeave = false)
                }
            }
            // The new version is installed now; it says so the next time it opens.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The person said no in Android's prompt: keep the file and the button.
                prefs.edit().remove(Prefs.KEY_UPDATE_INSTALLING).apply()
                if (info != null) current.value = UpdateState.Ready(info, installOnLeave = false)
            }
            else -> {
                prefs.edit().remove(Prefs.KEY_UPDATE_INSTALLING).apply()
                if (info != null) fail(app, info, UpdateProblem.INSTALL)
            }
        }
    }

    /** Registered by [HazeApp]: tells the updater when the person is in the app and when they leave it. */
    val lifecycle: Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            started++
            leftApp = false
        }

        override fun onActivityStopped(activity: Activity) {
            started = maxOf(0, started - 1)
            // A theme switch stops an activity and starts its copy: that is not leaving.
            if (started == 0 && !activity.isChangingConfigurations) {
                leftApp = true
                onLeave(activity.applicationContext)
            }
        }

        override fun onActivityResumed(activity: Activity) {
            onScreen = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (onScreen?.get() === activity) onScreen = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    // ---- Inside ----

    private fun onLeave(app: Context) {
        val s = current.value
        if (s is UpdateState.Ready && s.installOnLeave) install(app, s.info ?: return, asked = false)
    }

    private suspend fun checkAndReady(app: Context, info: UpdateInfo, file: File) {
        val problem = withContext(Dispatchers.IO) { checkApk(app, file) }
        if (problem != null) {
            fail(app, info, problem)
            return
        }
        val onLeave = UpdateLogic.installOnLeave(
            Build.VERSION.SDK_INT,
            info.version,
            canInstallApps(app),
            prefs(app).getString(Prefs.KEY_UPDATE_ASKED, null)
        )
        Log.d(TAG, "update ready: ${info.version} onLeave=$onLeave")
        current.value = UpdateState.Ready(info, onLeave)
        // Finished after the person had already left: install now, while nobody is looking.
        if (onLeave && leftApp) install(app, info, asked = false)
    }

    private fun install(app: Context, info: UpdateInfo, asked: Boolean) {
        val file = fileFor(app, info)
        if (!file.isFile) {
            current.value = UpdateState.Available(info)
            return
        }
        current.value = UpdateState.Installing(info)
        // Written at once, not later: a silent update ends this process soon after.
        prefs(app).edit().putString(Prefs.KEY_UPDATE_INSTALLING, info.version).commit()
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(app, file) }
                Log.d(TAG, "update install committed: ${info.version} asked=$asked")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update install could not start: ${info.version}", e)
                prefs(app).edit().remove(Prefs.KEY_UPDATE_INSTALLING).apply()
                fail(app, info, UpdateProblem.INSTALL)
            }
        }
    }

    private fun fail(app: Context, info: UpdateInfo, problem: UpdateProblem) {
        Log.w(TAG, "update problem: ${info.version} $problem")
        // A network failure is tried again on the next open; anything else waits for a tap.
        if (problem != UpdateProblem.DOWNLOAD) {
            prefs(app).edit().putString(Prefs.KEY_UPDATE_FAILED, UpdateLogic.failRecord(info.version, problem)).apply()
        }
        fileFor(app, info).delete()
        current.value = UpdateState.Failed(info, problem)
    }

    /** Writes the APK into an install session and hands it to Android. */
    private fun commit(app: Context, file: File) {
        val installer = app.packageManager.packageInstaller
        // An earlier try that is still open (waiting for a tap, or cut short) would only get in the way.
        for (old in installer.mySessions) {
            try {
                installer.abandonSession(old.sessionId)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusSender(app))
        }
    }

    private fun statusSender(app: Context): IntentSender {
        val intent = Intent(app, UpdateStatusReceiver::class.java).setAction(ACTION_STATUS)
        // Mutable: Android adds its answer to the intent before sending it back.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, 0, intent, flags).intentSender
    }

    /** Checks the downloaded file is this app, newer, and signed with the same key. */
    @Suppress("DEPRECATION")
    private fun checkApk(app: Context, file: File): UpdateProblem? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.path, flags)
        val own = try {
            pm.getPackageInfo(app.packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return UpdateLogic.checkApk(
            archivePackage = archive?.packageName,
            archiveVersion = archive?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L,
            archiveSigners = signers(archive),
            ownPackage = app.packageName,
            ownVersion = own?.let { PackageInfoCompat.getLongVersionCode(it) } ?: Long.MAX_VALUE,
            ownSigners = signers(own)
        )
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): List<ByteArray> {
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.signingInfo?.apkContentsSigners
        } else {
            info?.signatures
        }
        return list?.map { it.toByteArray() }.orEmpty()
    }

    /** Whether the person has allowed this app to install apps ("Install unknown apps"). */
    private fun canInstallApps(app: Context): Boolean = app.packageManager.canRequestPackageInstalls()

    private fun prefs(app: Context): SharedPreferences = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

    private fun updateDir(app: Context) = File(app.cacheDir, "update")

    /** The version is digits and dots only (see [UpdateLogic.parseLatest]), so it is safe in a file name. */
    private fun fileFor(app: Context, info: UpdateInfo) = File(updateDir(app), "HazeMapSG-${info.version}.apk")
}
