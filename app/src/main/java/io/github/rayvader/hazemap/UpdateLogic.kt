package io.github.rayvader.hazemap

import org.json.JSONObject

/**
 * The newest full release: its version text such as "1.0.32", where to download the APK, and its
 * "What's new" lines as plain text (empty when the release has none).
 */
data class UpdateInfo(val version: String, val downloadUrl: String, val notes: List<String> = emptyList()) {
    /** False when the release has no APK and only its web page can be opened. */
    val isApk: Boolean get() = downloadUrl.endsWith(".apk", ignoreCase = true)
}

/** Why a self-update stopped. [retry] says whether trying again can help. */
enum class UpdateProblem(val retry: Boolean) {
    /** The network failed. */
    DOWNLOAD(true),

    /** The file is not a whole APK. */
    DAMAGED(true),

    /** The file is a different app. */
    OTHER_APP(false),

    /** The file is not newer than the app on the phone. */
    NOT_NEWER(false),

    /** The file is not signed with this app's key. */
    OTHER_SIGNER(false),

    /** Android refused or failed the install. */
    INSTALL(true)
}

/** What GitHub gave back when the app asked for the newest release. */
sealed interface CheckResult {
    /** GitHub answered. [latest] is the newest full release, or null when there is none the app can use. */
    data class Reached(val latest: UpdateInfo?) : CheckResult

    /** The network failed, or GitHub refused (a rate limit, say). */
    object Unreachable : CheckResult
}

/** What the line at the end of the Now tab says. */
sealed interface VersionLine {
    /** Waiting for a tap. [installed] is the version running, or null if the phone would not say. */
    data class Resting(val installed: String?) : VersionLine

    /** Asking GitHub now. */
    object Checking : VersionLine

    /** The newest release is no newer than this copy. */
    data class UpToDate(val installed: String) : VersionLine

    /** A newer release is out: [version]. Its banner is on screen. */
    data class Newer(val version: String) : VersionLine

    /** No answer: the check says nothing about whether the app is current. */
    object Failed : VersionLine
}

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
        return if (url.startsWith(DOWNLOAD_PREFIX)) UpdateInfo(version, url, whatsNew(root.optString("body"))) else null
    }

    /** The same release (version and download), whatever its What's new lines say. */
    fun sameRelease(a: UpdateInfo?, b: UpdateInfo?): Boolean =
        a != null && b != null && a.version == b.version && a.downloadUrl == b.downloadUrl

    /** At most this many lines in the banner; more are summed up in one last line. */
    const val MAX_NOTES = 8

    /** Longer lines are cut here, so one long title cannot fill the banner. */
    const val MAX_NOTE_CHARS = 120

    /**
     * The "What's new" list from a release body written by CI (`.github/scripts/release_notes.py`), as plain
     * lines: the bullets under the "What's new" heading, up to the next heading, rule or changelog link.
     * Markdown is taken out (links keep their text) and the "(#12)" PR numbers are dropped. A body without
     * that heading gives its bullets anywhere; a body with no bullets gives nothing.
     */
    fun whatsNew(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val lines = body.replace("\r", "").lines()
        val start = lines.indexOfFirst { HEADING.matches(it.trim()) && it.contains("new", ignoreCase = true) }
        val section = if (start < 0) lines else lines.drop(start + 1).takeWhile { line ->
            val t = line.trim()
            !(HEADING.matches(t) || t == "---" || t.startsWith("**Full changelog", ignoreCase = true))
        }
        val items = section.mapNotNull { BULLET.matchEntire(it.trim())?.groupValues?.get(1) }
            .map { plain(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (items.size <= MAX_NOTES) return items
        return items.take(MAX_NOTES - 1) + "and ${items.size - (MAX_NOTES - 1)} more"
    }

    /** The banner's What's new text: one "• " line per note, or "" for none. */
    fun notesText(notes: List<String>): String = notes.joinToString("\n") { "• $it" }

    /** One bullet as plain text. */
    private fun plain(text: String): String {
        var t = text
        t = LINK.replace(t) { it.groupValues[1] }
        t = PR_NUMBER.replace(t, "")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (t.length > MAX_NOTE_CHARS) t.take(MAX_NOTE_CHARS - 1).trimEnd() + "…" else t
    }

    private val HEADING = Regex("^#{1,6}\\s.*")
    private val BULLET = Regex("^[-*+]\\s+(.*)")
    private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val PR_NUMBER = Regex("\\s*\\(#\\d+\\)")

    /** Ask GitHub again only after [CHECK_EVERY_MS]. A clock set back counts as due. */
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0 || nowMs < lastCheckMs || nowMs - lastCheckMs >= CHECK_EVERY_MS

    /** Show the banner for a newer version the user has not closed. */
    fun shouldShow(installed: String?, latest: UpdateInfo?, dismissedVersion: String?): Boolean =
        latest != null && Versions.isNewer(latest.version, installed) && latest.version != dismissedVersion

    /** "Checking…" stays up at least this long, as an answer in a blink looks like nothing happened. */
    const val MIN_CHECKING_MS = 700L

    /**
     * What the line says once a check is over. "Up to date" is said only when GitHub answered and this copy
     * is the newest release or newer (a test build from a branch is); no answer, or a version number that
     * cannot be read on either side, is [VersionLine.Failed], never a guess.
     */
    fun afterCheck(installed: String?, result: CheckResult): VersionLine {
        val latest = (result as? CheckResult.Reached)?.latest ?: return VersionLine.Failed
        val own = installed?.takeIf { Versions.parse(it) != null } ?: return VersionLine.Failed
        return if (Versions.isNewer(latest.version, own)) VersionLine.Newer(latest.version) else VersionLine.UpToDate(own)
    }

    /**
     * Checks a downloaded APK against the app on the phone: same app, newer, same signing key.
     * Android checks the key again when it installs, so a key list that cannot be read here is left to it.
     */
    fun checkApk(
        archivePackage: String?,
        archiveVersion: Long,
        archiveSigners: List<ByteArray>,
        ownPackage: String,
        ownVersion: Long,
        ownSigners: List<ByteArray>
    ): UpdateProblem? {
        if (archivePackage == null) return UpdateProblem.DAMAGED
        if (archivePackage != ownPackage) return UpdateProblem.OTHER_APP
        if (archiveVersion <= ownVersion) return UpdateProblem.NOT_NEWER
        if (archiveSigners.isNotEmpty() && ownSigners.isNotEmpty() &&
            archiveSigners.map { it.toList() }.toSet() != ownSigners.map { it.toList() }.toSet()
        ) {
            return UpdateProblem.OTHER_SIGNER
        }
        return null
    }

    /** What is kept when Android turned a no-tap install into a prompt: the version and the permission then. */
    fun askRecord(version: String, canInstallApps: Boolean): String = "$version|$canInstallApps"

    /**
     * Whether to install with no tap when the person leaves the app. Android 12 (API 31) and newer can
     * update an app with no tap once the person has allowed "Install unknown apps" for it; before that,
     * Android asks first. A no-tap try that Android still turned into a prompt is not repeated for that
     * version until that setting changes.
     */
    fun installOnLeave(sdk: Int, version: String, canInstallApps: Boolean, lastAsk: String?): Boolean =
        sdk >= 31 && canInstallApps && lastAsk != askRecord(version, canInstallApps)

    fun failRecord(version: String, problem: UpdateProblem): String = "$version|${problem.name}"

    /** The problem recorded for [version], or null when it has none (or the record is for another version). */
    fun failedProblem(record: String?, version: String): UpdateProblem? {
        val parts = record?.split('|') ?: return null
        if (parts.size != 2 || parts[0] != version) return null
        return UpdateProblem.entries.firstOrNull { it.name == parts[1] }
    }

    /** True when the app now runs the version an update was installing, or a later one. */
    fun justUpdated(installing: String?, installed: String?): Boolean =
        Versions.parse(installing) != null && Versions.parse(installed) != null &&
            !Versions.isNewer(installing, installed)
}
