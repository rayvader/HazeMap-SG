package io.github.rayvader.hazemap

import org.json.JSONObject

/** The newest full release: its version text such as "1.0.32" and where to download the APK. */
data class UpdateInfo(val version: String, val downloadUrl: String)

/** Version numbers such as "1.0.31" (or a release tag "v1.0.31"), compared number by number. */
object Versions {
    fun parse(text: String?): List<Int>? {
        val t = text?.trim()?.removePrefix("v")?.removePrefix("V").orEmpty()
        if (t.isEmpty()) return null
        return t.split(".").map { it.toIntOrNull() ?: return null }
    }

    /** Missing trailing parts count as zero, so "1.0" equals "1.0.0". */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** False when either side is not a version number, so a bad answer never shows a banner. */
    fun isNewer(candidate: String?, installed: String?): Boolean {
        val c = parse(candidate) ?: return false
        val i = parse(installed) ?: return false
        return compare(c, i) > 0
    }
}

object UpdateLogic {
    const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    /** Only downloads from this project's own releases are offered. */
    const val DOWNLOAD_PREFIX = "https://github.com/rayvader/HazeMap-SG/"

    /**
     * Reads GitHub's "latest release" answer. Drafts and pre-releases are skipped, and so is anything
     * without a version number or a download link inside this project.
     */
    fun parseLatest(body: String): UpdateInfo? {
        val root = JSONObject(body)
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null
        val version = root.optString("tag_name").removePrefix("v")
        if (Versions.parse(version) == null) return null

        var url = root.optString("html_url")
        val assets = root.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
        }
        return if (url.startsWith(DOWNLOAD_PREFIX)) UpdateInfo(version, url) else null
    }

    /** Ask GitHub again only after [CHECK_EVERY_MS]. A clock set back counts as due. */
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_EVERY_MS

    /** Show the banner for a newer version the user has not closed. */
    fun shouldShow(installed: String?, latest: UpdateInfo?, dismissedVersion: String?): Boolean =
        latest != null && Versions.isNewer(latest.version, installed) && latest.version != dismissedVersion
}
