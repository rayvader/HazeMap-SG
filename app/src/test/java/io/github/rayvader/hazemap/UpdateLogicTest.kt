package io.github.rayvader.hazemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private fun release(
        tag: String = "v1.0.40",
        draft: Boolean = false,
        pre: Boolean = false,
        assets: String = """[{"name":"HazeMapSG-1.0.40.apk","browser_download_url":"https://github.com/rayvader/HazeMap-SG/releases/download/v1.0.40/HazeMapSG-1.0.40.apk"}]""",
        page: String = "https://github.com/rayvader/HazeMap-SG/releases/tag/v1.0.40"
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"$page","assets":$assets}"""

    // ---- Versions ----

    @Test
    fun versionsAreComparedNumberByNumber() {
        assertTrue(Versions.isNewer("1.0.32", "1.0.31"))
        assertTrue(Versions.isNewer("1.0.10", "1.0.9"))
        assertTrue(Versions.isNewer("1.1.0", "1.0.99"))
        assertTrue(Versions.isNewer("2.0", "1.9.9"))
        assertFalse(Versions.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(Versions.isNewer("1.0.31", "1.0.31"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun aTagWithAVIsReadLikeAVersion() {
        assertEquals(listOf(1, 0, 31), Versions.parse("v1.0.31"))
        assertTrue(Versions.isNewer("v1.0.32", "1.0.31"))
    }

    @Test
    fun textThatIsNotAVersionNeverCountsAsNewer() {
        assertNull(Versions.parse(null))
        assertNull(Versions.parse(""))
        assertNull(Versions.parse("latest"))
        assertNull(Versions.parse("1.0.x"))
        assertNull(Versions.parse("1.0.31-beta"))
        assertFalse(Versions.isNewer("garbage", "1.0.31"))
        assertFalse(Versions.isNewer("1.0.32", null))
        assertFalse(Versions.isNewer("1.0.32", "dev"))
    }

    // ---- GitHub answer ----

    @Test
    fun theRealLatestReleaseIsRead() {
        val info = UpdateLogic.parseLatest(fixture("github-release-latest.json"))!!
        assertTrue(Versions.parse(info.version) != null)
        assertTrue(info.downloadUrl.endsWith(".apk"))
        assertTrue(info.downloadUrl.startsWith(UpdateLogic.DOWNLOAD_PREFIX))
    }

    @Test
    fun theApkLinkIsPreferred() {
        val info = UpdateLogic.parseLatest(release())!!
        assertEquals("1.0.40", info.version)
        assertEquals(
            "https://github.com/rayvader/HazeMap-SG/releases/download/v1.0.40/HazeMapSG-1.0.40.apk",
            info.downloadUrl
        )
    }

    @Test
    fun withoutAnApkTheReleasePageIsUsed() {
        val info = UpdateLogic.parseLatest(release(assets = "[]"))!!
        assertEquals("https://github.com/rayvader/HazeMap-SG/releases/tag/v1.0.40", info.downloadUrl)
    }

    @Test
    fun draftsAndPreReleasesAreSkipped() {
        assertNull(UpdateLogic.parseLatest(release(draft = true)))
        assertNull(UpdateLogic.parseLatest(release(pre = true)))
    }

    @Test
    fun aTagThatIsNotAVersionIsSkipped() {
        assertNull(UpdateLogic.parseLatest(release(tag = "nightly")))
        assertNull(UpdateLogic.parseLatest("""{"message":"Not Found"}"""))
    }

    @Test
    fun linksOutsideThisProjectAreNotOffered() {
        val other = """[{"name":"x.apk","browser_download_url":"https://evil.example.com/x.apk"}]"""
        assertNull(UpdateLogic.parseLatest(release(assets = other, page = "https://evil.example.com/page")))
        assertNull(UpdateLogic.parseLatest(release(assets = "[]", page = "http://github.com/rayvader/HazeMap-SG/releases")))
    }

    // ---- When to ask and when to show ----

    @Test
    fun aCheckIsDueOnlyEverySixHours() {
        val hour = 3_600_000L
        assertTrue(UpdateLogic.isDue(0L, 5 * hour))
        assertFalse(UpdateLogic.isDue(10 * hour, 15 * hour))
        assertTrue(UpdateLogic.isDue(10 * hour, 16 * hour))
        assertTrue(UpdateLogic.isDue(10 * hour, 30 * hour))
    }

    @Test
    fun aClockSetBackCountsAsDue() {
        assertTrue(UpdateLogic.isDue(10_000L, 5_000L))
    }

    @Test
    fun theBannerShowsForANewerVersionOnly() {
        val latest = UpdateInfo("1.0.32", "https://github.com/rayvader/HazeMap-SG/x.apk")
        assertTrue(UpdateLogic.shouldShow("1.0.31", latest, null))
        assertFalse(UpdateLogic.shouldShow("1.0.32", latest, null))
        assertFalse(UpdateLogic.shouldShow("1.0.33", latest, null))
        assertFalse(UpdateLogic.shouldShow("1.0.31", null, null))
    }

    @Test
    fun aClosedBannerStaysClosedUntilAnotherVersionComes() {
        val v32 = UpdateInfo("1.0.32", "https://github.com/rayvader/HazeMap-SG/a.apk")
        val v33 = UpdateInfo("1.0.33", "https://github.com/rayvader/HazeMap-SG/b.apk")
        assertFalse(UpdateLogic.shouldShow("1.0.31", v32, "1.0.32"))
        assertTrue(UpdateLogic.shouldShow("1.0.31", v33, "1.0.32"))
    }

    // ---- The version line at the end of the Now tab ----

    private fun newest(version: String) =
        UpdateInfo(version, "https://github.com/rayvader/HazeMap-SG/releases/download/v$version/HazeMapSG-$version.apk")

    @Test
    fun theLineSaysUpToDateWhenGitHubAnsweredAndThisCopyIsTheNewestOrNewer() {
        assertEquals(VersionLine.UpToDate("1.0.106"), UpdateLogic.afterCheck("1.0.106", CheckResult.Reached(newest("1.0.106"))))
        // A test build from a branch is newer than the newest release, and is up to date too.
        assertEquals(VersionLine.UpToDate("1.0.110"), UpdateLogic.afterCheck("1.0.110", CheckResult.Reached(newest("1.0.106"))))
    }

    @Test
    fun theLineSaysNewerWhenAReleaseIsAhead() {
        assertEquals(VersionLine.Newer("1.0.107"), UpdateLogic.afterCheck("1.0.106", CheckResult.Reached(newest("1.0.107"))))
        // Numbers, not text: 100 is ahead of 99.
        assertEquals(VersionLine.Newer("1.0.100"), UpdateLogic.afterCheck("1.0.99", CheckResult.Reached(newest("1.0.100"))))
    }

    @Test
    fun noAnswerIsNeverTurnedIntoUpToDate() {
        assertEquals(VersionLine.Failed, UpdateLogic.afterCheck("1.0.106", CheckResult.Unreachable))
        // GitHub answered, but with no release the app can use.
        assertEquals(VersionLine.Failed, UpdateLogic.afterCheck("1.0.106", CheckResult.Reached(null)))
        // This copy's own version cannot be read, so nothing can be compared.
        assertEquals(VersionLine.Failed, UpdateLogic.afterCheck(null, CheckResult.Reached(newest("1.0.107"))))
        assertEquals(VersionLine.Failed, UpdateLogic.afterCheck("dev", CheckResult.Reached(newest("1.0.107"))))
    }

    @Test
    fun aNewerAnswerShowsTheBannerAgainOnceTheClosedVersionIsForgotten() {
        // The check forgets the version the person closed, since they asked.
        assertFalse(UpdateLogic.shouldShow("1.0.106", newest("1.0.107"), "1.0.107"))
        assertTrue(UpdateLogic.shouldShow("1.0.106", newest("1.0.107"), null))
    }

    @Test
    fun checkingStaysUpLongEnoughToSee() {
        assertTrue(UpdateLogic.MIN_CHECKING_MS in 500L..1500L)
    }

    // ---- Self-update ----

    private val pkg = "io.github.rayvader.hazemap"
    private val key = listOf(byteArrayOf(1, 2, 3))

    private fun check(
        archivePackage: String? = pkg,
        archiveVersion: Long = 41,
        archiveSigners: List<ByteArray> = key,
        ownVersion: Long = 40,
        ownSigners: List<ByteArray> = listOf(byteArrayOf(1, 2, 3))
    ) = UpdateLogic.checkApk(archivePackage, archiveVersion, archiveSigners, pkg, ownVersion, ownSigners)

    @Test
    fun aNewerCopySignedWithTheSameKeyPasses() {
        assertNull(check())
    }

    @Test
    fun aFileThatIsNotAnApkIsDamaged() {
        assertEquals(UpdateProblem.DAMAGED, check(archivePackage = null))
    }

    @Test
    fun anotherAppIsRefused() {
        assertEquals(UpdateProblem.OTHER_APP, check(archivePackage = "com.example.other"))
    }

    @Test
    fun theSameOrAnOlderVersionIsRefused() {
        assertEquals(UpdateProblem.NOT_NEWER, check(archiveVersion = 40))
        assertEquals(UpdateProblem.NOT_NEWER, check(archiveVersion = 39))
    }

    @Test
    fun anotherKeyIsRefused() {
        assertEquals(UpdateProblem.OTHER_SIGNER, check(archiveSigners = listOf(byteArrayOf(9, 9))))
        assertEquals(UpdateProblem.OTHER_SIGNER, check(archiveSigners = key + listOf(byteArrayOf(9))))
    }

    @Test
    fun anUnreadableKeyListIsLeftToAndroidsOwnCheck() {
        assertNull(check(archiveSigners = emptyList()))
        assertNull(check(ownSigners = emptyList()))
    }

    @Test
    fun noTapInstallsNeedAndroid12AndTheInstallAppsPermission() {
        assertFalse(UpdateLogic.installOnLeave(30, "1.0.41", true, null))
        // Before the person allows "Install unknown apps", Android asks first.
        assertFalse(UpdateLogic.installOnLeave(34, "1.0.41", false, null))
        assertTrue(UpdateLogic.installOnLeave(31, "1.0.41", true, null))
        assertTrue(UpdateLogic.installOnLeave(34, "1.0.41", true, null))
    }

    @Test
    fun aNoTapTryThatAndroidTurnedIntoAPromptIsNotRepeated() {
        val asked = UpdateLogic.askRecord("1.0.41", true)
        assertFalse(UpdateLogic.installOnLeave(34, "1.0.41", true, asked))
        // A newer version gets its own try.
        assertTrue(UpdateLogic.installOnLeave(34, "1.0.42", true, asked))
    }

    @Test
    fun aFailureIsRememberedForItsVersionOnly() {
        val record = UpdateLogic.failRecord("1.0.41", UpdateProblem.OTHER_SIGNER)
        assertEquals(UpdateProblem.OTHER_SIGNER, UpdateLogic.failedProblem(record, "1.0.41"))
        assertNull(UpdateLogic.failedProblem(record, "1.0.42"))
        assertNull(UpdateLogic.failedProblem(null, "1.0.41"))
        assertNull(UpdateLogic.failedProblem("garbage", "1.0.41"))
        assertNull(UpdateLogic.failedProblem("1.0.41|NOPE", "1.0.41"))
    }

    @Test
    fun anUpdateCountsAsDoneOnceThatVersionOrALaterOneRuns() {
        assertTrue(UpdateLogic.justUpdated("1.0.41", "1.0.41"))
        assertTrue(UpdateLogic.justUpdated("1.0.41", "1.0.42"))
        assertFalse(UpdateLogic.justUpdated("1.0.41", "1.0.40"))
        assertFalse(UpdateLogic.justUpdated(null, "1.0.41"))
        assertFalse(UpdateLogic.justUpdated("1.0.41", null))
    }

    @Test
    fun onlyAnApkLinkCanBeInstalledByTheApp() {
        assertTrue(UpdateInfo("1.0.41", "https://github.com/rayvader/HazeMap-SG/x.apk").isApk)
        assertFalse(UpdateInfo("1.0.41", "https://github.com/rayvader/HazeMap-SG/releases/tag/v1.0.41").isApk)
    }

    // ---- What's new ----

    /** A body as CI writes it: .github/scripts/release_notes.py, then the "Built from" line. */
    private val ciBody = """
        ## What's new

        - A version line at the end of the Now tab, with Check for updates (#23)
        - The widget clock is **bold**, see [the README](https://github.com/rayvader/HazeMap-SG#readme) (#22)
        - `fix`: one more

        **Full changelog:** https://github.com/rayvader/HazeMap-SG/compare/v1.0.106...v1.0.120

        ---
        Built from `main` (`31ad728`). Download the APK on your phone and open it to install.
    """.trimIndent()

    @Test
    fun whatsNewIsReadAsPlainLines() {
        assertEquals(
            listOf(
                "A version line at the end of the Now tab, with Check for updates",
                "The widget clock is bold, see the README",
                "fix: one more"
            ),
            UpdateLogic.whatsNew(ciBody)
        )
    }

    @Test
    fun theReleaseCarriesItsWhatsNew() {
        val body = org.json.JSONObject.quote(ciBody)
        val json = release().dropLast(1) + ""","body":$body}"""
        assertEquals(3, UpdateLogic.parseLatest(json)!!.notes.size)
    }

    @Test
    fun anOldBodyWithNoListGivesNoNotes() {
        assertEquals(emptyList<String>(), UpdateLogic.parseLatest(fixture("github-release-latest.json"))!!.notes)
        assertEquals(emptyList<String>(), UpdateLogic.whatsNew(null))
        assertEquals(emptyList<String>(), UpdateLogic.whatsNew("Built from main (abc). Download the APK."))
        assertEquals(emptyList<String>(), UpdateLogic.parseLatest(release())!!.notes)
    }

    @Test
    fun aLongListIsCutWithACount() {
        val body = "## What's new\n\n" + (1..12).joinToString("\n") { "- change $it (#$it)" }
        val notes = UpdateLogic.whatsNew(body)
        assertEquals(UpdateLogic.MAX_NOTES, notes.size)
        assertEquals("change 7", notes[6])
        assertEquals("and 5 more", notes.last())
    }

    @Test
    fun aLongLineIsCut() {
        val note = UpdateLogic.whatsNew("- " + "x".repeat(300)).single()
        assertEquals(UpdateLogic.MAX_NOTE_CHARS, note.length)
        assertTrue(note.endsWith("…"))
    }

    @Test
    fun theBannerTextHasOneBulletPerNote() {
        assertEquals("• a\n• b", UpdateLogic.notesText(listOf("a", "b")))
        assertEquals("", UpdateLogic.notesText(emptyList()))
    }

    @Test
    fun theSameReleaseIgnoresItsNotes() {
        val a = UpdateInfo("1.0.41", "https://github.com/rayvader/HazeMap-SG/x.apk")
        assertTrue(UpdateLogic.sameRelease(a, a.copy(notes = listOf("new"))))
        assertFalse(UpdateLogic.sameRelease(a, a.copy(version = "1.0.42")))
        assertFalse(UpdateLogic.sameRelease(null, a))
    }
}
