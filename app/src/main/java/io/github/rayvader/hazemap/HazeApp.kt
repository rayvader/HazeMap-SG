package io.github.rayvader.hazemap

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.osmdroid.config.Configuration
import java.io.File

class HazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before the first activity, so the app opens in the saved theme with no flash of the other one.
        AppCompatDelegate.setDefaultNightMode(ThemeStore.load(this).nightMode)
        // osmdroid needs a user agent, and its tile cache must sit in app storage: it defaults to
        // shared storage, which apps cannot write to on current Android.
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        cfg.setOsmdroidBasePath(base)
        cfg.setOsmdroidTileCache(File(base, "tiles"))
    }
}
