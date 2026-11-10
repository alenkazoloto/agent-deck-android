package dev.agentdeck.companion

import com.github.claudeagents.core.mobile.MobileProtocol
import dev.agentdeck.companion.data.AppUpdate
import dev.agentdeck.companion.data.UpdateRelease
import dev.agentdeck.companion.data.UpdateState
import dev.agentdeck.companion.fixture.DeckFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-update decisions, which are the whole of how a **sideloaded** app ever leaves the
 * build it was handed — nothing else on the phone will offer it one.
 *
 * That makes both wrong answers expensive, and each has a case here: an update the app fails to
 * see strands the user on a build the machine has moved past, and an update it invents is a
 * download that cannot install. The install itself is Android's, and its refusals belong to it.
 */
class AppUpdateTest {

    /**
     * **The bytes `releases/latest/download/latest.json` actually served**, copied off the live
     * release rather than written here: a fixture the test's own author composed agrees with the
     * test and not with the producer, and the producer is a Bash script and a `python3` heredoc
     * in `scripts/publish-mobile.sh` that nothing else reads.
     */
    private val published = """
        {
          "v": 1,
          "versionName": "1.1",
          "versionCode": 2,
          "variant": "debug",
          "apk": "agent-deck-1.1-debug.apk",
          "url": "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.1/agent-deck-1.1-debug.apk",
          "sizeBytes": 12990775,
          "sha256": "2f4aba33081a5c98e342e40390264e9564b2546e993d63eb128d2bc65976f5ae",
          "minSdk": 26,
          "releaseUrl": "https://github.com/alenkazoloto/agent-deck-android/releases/tag/v1.1"
        }
    """.trimIndent()

    private fun parse(json: String): UpdateRelease? =
        MobileProtocol.parseObject(json)?.let(UpdateRelease::fromJson)

    private fun release(
        versionCode: Long = 3,
        versionName: String = "1.2",
        variant: String = "release",
        minSdk: Int = 26,
    ) = UpdateRelease(
        versionName = versionName,
        versionCode = versionCode,
        variant = variant,
        apkName = "agent-deck.apk",
        apkUrl = "https://github.com/alenkazoloto/agent-deck-android/releases/download/v$versionName/agent-deck.apk",
        sizeBytes = 6_711_000,
        sha256 = null,
        minSdk = minSdk,
        releaseUrl = "",
    )

    // ---- the manifest ------------------------------------------------------------------

    @Test
    fun `the published manifest parses whole`() {
        val release = requireNotNull(parse(published)) { "the published manifest must parse" }
        assertEquals("1.1", release.versionName)
        assertEquals(2L, release.versionCode)
        assertEquals("debug", release.variant)
        assertEquals("agent-deck-1.1-debug.apk", release.apkName)
        // The size the release actually serves — checked against the download's own bytes when
        // this manifest was taken, so a wrong number here is a wrong progress bar.
        assertEquals(12_990_775L, release.sizeBytes)
        assertEquals(26, release.minSdk)
        assertEquals("2f4aba33081a5c98e342e40390264e9564b2546e993d63eb128d2bc65976f5ae", release.sha256)
        assertTrue(release.apkUrl.endsWith("agent-deck-1.1-debug.apk"))
    }

    /**
     * The manifest is the *only* input to a download that ends at Android's package installer,
     * so an unpinned `url` is a route from "whatever answers that address" into it. This is the
     * case that separates a checked address from a trusted one.
     */
    @Test
    fun `a manifest naming a download somewhere else is refused`() {
        val elsewhere = published.replace(
            "https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.1/agent-deck-1.1-debug.apk",
            "https://releases.example.com/agent-deck-1.1.apk",
        )
        assertNull(parse(elsewhere))
        // Right host, wrong scheme: a plaintext download of an APK is the same attack with
        // one fewer step.
        assertFalse(
            AppUpdate.isTrustedApkUrl("http://github.com/alenkazoloto/agent-deck-android/x.apk"),
        )
        // The bytes are served from GitHub's asset host after a redirect, so it is trusted too.
        assertTrue(AppUpdate.isTrustedApkUrl("https://objects.githubusercontent.com/x.apk"))
        // Not a suffix match on the *string*: this host is not GitHub.
        assertFalse(AppUpdate.isTrustedApkUrl("https://notgithub.com/x.apk"))
        assertFalse(AppUpdate.isTrustedApkUrl("https://github.com.evil.test/x.apk"))
    }

    @Test
    fun `a manifest that cannot be acted on is no update at all`() {
        assertNull("no versionCode", parse(published.replace("\"versionCode\": 2", "\"versionCode\": 0")))
        assertNull("no versionName", parse(published.replace("\"versionName\": \"1.1\"", "\"versionName\": \"\"")))
        assertNull("no url", parse(published.replace("\"url\"", "\"link\"")))
        assertNull("not JSON at all", parse("<html>404</html>"))
    }

    /** A manifest from before a field existed must not read as a refusal. */
    @Test
    fun `an older manifest keeps working`() {
        val old = """{"versionName":"1.2","versionCode":3,
            "url":"https://github.com/alenkazoloto/agent-deck-android/releases/download/v1.2/a.apk"}"""
        val release = parse(old)
        assertNotNull(release)
        assertEquals(0, release!!.minSdk)
        assertNull(release.sha256)
        assertEquals("a.apk", release.apkName)
    }

    // ---- what counts as an update ------------------------------------------------------

    @Test
    fun `only a strictly newer build is an update`() {
        fun offered(installed: Long, published: Long) =
            UpdateState(release = release(versionCode = published), installedCode = installed).available
        assertTrue(offered(installed = 2, published = 3))
        assertFalse("the same build is not an update", offered(installed = 3, published = 3))
        // A release rolled back on the publisher's side must never install *backwards*: Android
        // refuses the downgrade anyway, so offering it is a button that can only fail.
        assertFalse("a downgrade is not an update", offered(installed = 4, published = 3))
    }

    @Test
    fun `a build this phone cannot run is not offered`() {
        val state = UpdateState(release = release(minSdk = 30), installedCode = 2)
        assertTrue(state.available)
        assertTrue(AppUpdate.tooOld(state.release, sdkInt = 26))
        assertFalse(AppUpdate.bannerWorthy(state, notices = true, sdkInt = 26))
        assertTrue(AppUpdate.status(state, sdkInt = 26).contains("needs Android API 30"))
        assertFalse(AppUpdate.tooOld(state.release, sdkInt = 34))
    }

    // ---- the banner --------------------------------------------------------------------

    @Test
    fun `the banner appears only while it changes a decision`() {
        val offered = UpdateState(release = release(versionCode = 3), installedCode = 2)
        assertTrue(AppUpdate.bannerWorthy(offered, notices = true, sdkInt = 34))
        assertFalse(
            "notices off is an off switch, not a mute",
            AppUpdate.bannerWorthy(offered, notices = false, sdkInt = 34),
        )
        assertFalse(
            "this version was waved away",
            AppUpdate.bannerWorthy(offered.copy(dismissedCode = 3), notices = true, sdkInt = 34),
        )
        assertTrue(
            "a newer one raises it again",
            AppUpdate.bannerWorthy(offered.copy(dismissedCode = 2), notices = true, sdkInt = 34),
        )
        assertFalse(
            "up to date has nothing to say",
            AppUpdate.bannerWorthy(offered.copy(installedCode = 3), notices = true, sdkInt = 34),
        )
    }

    /**
     * Work the user asked for outranks the announcement switch. A download whose progress row
     * disappears because notices were turned off mid-transfer is a download with nowhere to
     * report, and a file waiting for the installer with no visible Install button is a build
     * the user paid for and cannot reach.
     */
    @Test
    fun `a download in flight keeps its row whatever the switch says`() {
        val downloading = UpdateState(release = release(), installedCode = 2, downloadPercent = 42)
        assertTrue(AppUpdate.bannerWorthy(downloading, notices = false, sdkInt = 34))
        val ready = UpdateState(release = release(), installedCode = 2, readyApk = "/tmp/a.apk", dismissedCode = 3)
        assertTrue(AppUpdate.bannerWorthy(ready, notices = false, sdkInt = 34))
    }

    // ---- the one sentence both surfaces read -------------------------------------------

    @Test
    fun `the status line names the state it is in`() {
        val installed = UpdateState(release = release(versionCode = 2, versionName = "1.1"), installedCode = 2)
        assertEquals("Up to date.", AppUpdate.status(installed, sdkInt = 34))
        assertEquals("Not checked yet.", AppUpdate.status(UpdateState(installedCode = 2), sdkInt = 34))
        assertEquals("Checking…", AppUpdate.status(installed.copy(checking = true), sdkInt = 34))

        val offered = UpdateState(release = release(versionCode = 3, versionName = "1.2"), installedCode = 2)
        assertEquals("1.2 is available.", AppUpdate.status(offered, sdkInt = 34))
        // A debug build is installable and is not a release artifact; the offer says which.
        assertTrue(
            AppUpdate.status(
                offered.copy(release = release(versionCode = 3, variant = "debug")),
                sdkInt = 34,
            ).contains("debug build"),
        )
        assertEquals(
            "Downloading 1.2 — 42%",
            AppUpdate.status(offered.copy(downloadPercent = 42), sdkInt = 34),
        )
    }

    /**
     * The state that made the ordering a decision: the download **succeeded** and the install
     * was refused, so both `readyApk` and `error` are set. Reporting that as "Android's
     * installer takes it from here" hides the one sentence that says what to do about it.
     */
    @Test
    fun `a refused install is read out, not hidden behind the download that worked`() {
        val stuck = UpdateState(
            release = release(),
            installedCode = 2,
            readyApk = "/data/cache/updates/agent-deck-3.apk",
            error = "Android has not been allowed to install apps from Agent Deck.",
        )
        assertEquals(
            "Android has not been allowed to install apps from Agent Deck.",
            AppUpdate.status(stuck, sdkInt = 34),
        )
    }

    // ---- the check's own clock ---------------------------------------------------------

    @Test
    fun `the automatic check runs on an interval it cannot be cheated out of`() {
        val now = 1_800_000_000_000L
        assertTrue("never checked", AppUpdate.shouldCheck(now, 0))
        assertFalse("just checked", AppUpdate.shouldCheck(now, now - 1000))
        assertTrue("interval elapsed", AppUpdate.shouldCheck(now, now - AppUpdate.CHECK_INTERVAL_MS))
        // A clock that moved backwards — timezone, NTP, a user setting the date — would
        // otherwise park the next check up to twelve hours in the future.
        assertTrue("the clock moved back", AppUpdate.shouldCheck(now, now + 60_000))
    }

    @Test
    fun `a checksum is compared when the manifest carries one`() {
        assertTrue("absent is not a failure", AppUpdate.digestMatches(null, "abc"))
        assertTrue(AppUpdate.digestMatches("ABC123", "abc123"))
        assertFalse(AppUpdate.digestMatches("abc123", "abc124"))
    }

    // ---- the fixtures the screenshots are taken of --------------------------------------

    /**
     * The golden pair is only evidence if the two states differ in the property being
     * photographed — `settings` must be the *checked and current* control, not one that has
     * never looked, or "Up to date." and "Not checked yet." would be the same shot's caption.
     */
    @Test
    fun `the settings fixtures differ in exactly the published version`() {
        val current = DeckFixtures.byName("settings")!!.update
        val updated = DeckFixtures.byName("settings-update")!!.update
        assertFalse(current.available)
        assertEquals("Up to date.", AppUpdate.status(current, sdkInt = 34))
        assertTrue(updated.available)
        assertEquals(current.installedCode, updated.installedCode)
        assertEquals(current.installedCode + 1, updated.release!!.versionCode)
        assertTrue(AppUpdate.bannerWorthy(updated, notices = true, sdkInt = 34))
    }
}
