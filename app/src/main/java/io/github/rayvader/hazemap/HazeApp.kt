package io.github.rayvader.hazemap

import android.app.Application
import org.osmdroid.config.Configuration
import java.io.File

class HazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // osmdroid needs a user agent, and its tile cache must sit in app storage: it defaults to
        // shared storage, which apps cannot write to on current Android.
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        val base = File(cacheDir, "osmdroid")
        cfg.setOsmdroidBasePath(base)
        cfg.setOsmdroidTileCache(File(base, "tiles"))
    }
}
