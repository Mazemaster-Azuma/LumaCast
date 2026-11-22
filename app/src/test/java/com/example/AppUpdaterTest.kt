package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.AppPreferencesRepository
import com.example.core.common.NetworkHealthState
import com.example.core.common.UserSettings
import com.example.core.update.ApkDownloadResult
import com.example.core.update.ApkInstallationManager
import com.example.core.update.ApkVerificationManager
import com.example.core.update.ApkVerificationResult
import com.example.core.update.AppUpdateState
import com.example.core.update.GitHubApkAsset
import com.example.core.update.GitHubReleaseInfo
import com.example.core.update.GitHubReleaseRepository
import com.example.core.update.ManifestParseResult
import com.example.core.update.NetworkConnectivityChecker
import com.example.core.update.ParsedApkMetadata
import com.example.core.update.SemanticVersion
import com.example.core.update.UpdateCheckResult
import com.example.core.update.UpdateDownloadManager
import com.example.core.update.UpdateManager
import com.example.core.update.UpdateManifestParser
import com.example.core.update.VersionComparator
import com.example.ui.components.AppUpdateDialog
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.LumaCastTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdaterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun releaseJson(
        tag: String = "v1.2.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkName: String = "LumaCast-$tag.apk",
        withManifest: Boolean = true,
        withShaAsset: Boolean = true
    ): String {
        val assets = buildList {
            add(
                """
                {
                  "name": "$apkName",
                  "browser_download_url": "https://github.com/panther/lumacast/releases/download/$tag/$apkName",
                  "size": 24500000,
                  "content_type": "application/vnd.android.package-archive"
                }
                """.trimIndent()
            )
            if (withShaAsset) {
                add(
                    """
                    {
                      "name": "$apkName.sha256",
                      "browser_download_url": "https://github.com/panther/lumacast/releases/download/$tag/$apkName.sha256",
                      "size": 90,
                      "content_type": "text/plain"
                    }
                    """.trimIndent()
                )
            }
            if (withManifest) {
                add(
                    """
                    {
                      "name": "update-manifest.json",
                      "browser_download_url": "https://github.com/panther/lumacast/releases/download/$tag/update-manifest.json",
                      "size": 420,
                      "content_type": "application/json"
                    }
                    """.trimIndent()
                )
            }
        }.joinToString(",")

        return """
            {
              "tag_name": "$tag",
              "name": "LumaCast $tag",
              "draft": $draft,
              "prerelease": $prerelease,
              "published_at": "2026-11-22T10:00:00Z",
              "html_url": "https://github.com/panther/lumacast/releases/tag/$tag",
              "body": "Smooth hardware decoding and Subtitle updates.",
              "assets": [$assets]
            }
        """.trimIndent()
    }

    private fun manifestJson(
        versionName: String = "1.2.0",
        versionCode: Int = 5,
        sha256: String = SAMPLE_SHA256,
        apkUrl: String = "https://github.com/panther/lumacast/releases/download/v1.2.0/LumaCast-v1.2.0.apk",
        mandatoryUpdate: Boolean = false
    ): String = """
        {
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "releaseTag": "v$versionName",
          "releaseName": "LumaCast v$versionName",
          "releaseNotes": "Verified manifest release notes.",
          "minimumSupportedVersion": 1,
          "apkUrl": "$apkUrl",
          "sha256": "$sha256",
          "fileSizeBytes": 24500000,
          "mandatoryUpdate": $mandatoryUpdate
        }
    """.trimIndent()

    // ---- Scenarios 1, 2, 3, 4, 5, 6, 20: Controlled Startup & Manual Checking Policy ----

    @Test
    fun `scenario 1 - fresh app launch online when up to date finishes quietly without dialog`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context).apply {
            setAutoCheckUpdates(true)
            setGithubUpdateRepo("panther/lumacast")
            setSkippedUpdateTag("")
            setLastUpdateCheckMs(0L)
        }
        val calls = AtomicInteger(0)
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.2.0" },
            httpGet = { url ->
                calls.incrementAndGet()
                when {
                    url.endsWith("/releases/latest") -> 200 to releaseJson(tag = "v1.2.0")
                    url.endsWith("update-manifest.json") -> 200 to manifestJson(versionName = "1.2.0", versionCode = 5)
                    else -> 404 to ""
                }
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { true },
                preferencesRepository = prefs,
                isCastingActive = { false },
                installedVersionCodeProvider = { 5 },
                installedVersionNameProvider = { "1.2.0" },
                defaultRepoSlugProvider = { "panther/lumacast" },
                nowMsProvider = { 1_700_000_123_000L },
                scope = scope
            )

            manager.triggerStartupCheckIfEligible(startupDelayMs = 0L)
            val settled = withTimeout(5_000L) {
                manager.updateState.first { it is AppUpdateState.UpToDate }
            }

            assertTrue(settled is AppUpdateState.UpToDate)
            assertFalse(manager.showUpdateDialog.value)
            assertEquals(1_700_000_123_000L, prefs.settingsFlow.first().lastUpdateCheckMs)
            assertEquals(2, calls.get()) // /releases/latest + update-manifest.json
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scenario 2 - fresh app launch online when newer version exists prompts dialog`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context).apply {
            setAutoCheckUpdates(true)
            setGithubUpdateRepo("panther/lumacast")
            setSkippedUpdateTag("")
        }
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.0.0" },
            httpGet = { url ->
                when {
                    url.endsWith("/releases/latest") -> 200 to releaseJson(tag = "v1.2.0")
                    url.endsWith("update-manifest.json") -> 200 to manifestJson(versionName = "1.2.0", versionCode = 6)
                    else -> 404 to ""
                }
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { true },
                preferencesRepository = prefs,
                isCastingActive = { false },
                installedVersionCodeProvider = { 5 },
                installedVersionNameProvider = { "1.0.0" },
                defaultRepoSlugProvider = { "panther/lumacast" },
                scope = scope
            )

            manager.triggerStartupCheckIfEligible(startupDelayMs = 0L)
            val settled = withTimeout(5_000L) {
                manager.updateState.first { it is AppUpdateState.UpdateAvailable }
            }

            val available = settled as AppUpdateState.UpdateAvailable
            assertEquals(6, available.release.versionCode)
            assertTrue(manager.showUpdateDialog.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scenario 3 and 4 - offline launch skips check and never rechecks when connectivity returns`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context).apply {
            setAutoCheckUpdates(true)
            setGithubUpdateRepo("panther/lumacast")
        }
        var online = false
        val networkCalls = AtomicInteger(0)
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.0.0" },
            httpGet = {
                networkCalls.incrementAndGet()
                200 to releaseJson()
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { online },
                preferencesRepository = prefs,
                isCastingActive = { false },
                installedVersionCodeProvider = { 1 },
                installedVersionNameProvider = { "1.0.0" },
                defaultRepoSlugProvider = { "panther/lumacast" },
                scope = scope
            )

            // App starts while offline -> must skip cleanly with zero requests
            manager.triggerStartupCheckIfEligible(startupDelayMs = 0L)
            delay(150L)
            assertEquals(AppUpdateState.Idle, manager.updateState.value)
            assertEquals(0, networkCalls.get())

            // Device later comes online and triggerStartupCheckIfEligible is called again (e.g., Activity recreation)
            online = true
            manager.triggerStartupCheckIfEligible(startupDelayMs = 0L)
            delay(150L)
            assertEquals(0, networkCalls.get()) // Strict single cold-start policy
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scenario 5 and 6 - repeated activity opens skipped while manual checks work and deduplicate concurrent taps`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context).apply {
            setAutoCheckUpdates(true)
            setGithubUpdateRepo("panther/lumacast")
        }
        val firstCallStarted = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val releaseApiCalls = AtomicInteger(0)
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.0.0" },
            httpGet = { url ->
                if (url.endsWith("/releases/latest")) {
                    val callNum = releaseApiCalls.incrementAndGet()
                    if (callNum == 1) {
                        firstCallStarted.complete(Unit)
                        gate.await()
                    }
                    200 to releaseJson(tag = "v1.0.0", withManifest = false)
                } else {
                    404 to ""
                }
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { true },
                preferencesRepository = prefs,
                isCastingActive = { false },
                installedVersionCodeProvider = { 1 },
                installedVersionNameProvider = { "1.0.0" },
                defaultRepoSlugProvider = { "panther/lumacast" },
                scope = scope
            )

            // Rapid concurrent manual taps while first request is still in-flight
            manager.checkManually()
            withTimeout(5_000L) { firstCallStarted.await() }
            manager.checkManually()
            delay(100L)
            assertEquals(1, releaseApiCalls.get())
            assertEquals(AppUpdateState.Checking, manager.updateState.value)

            gate.complete(Unit)
            withTimeout(5_000L) {
                manager.updateState.first { it is AppUpdateState.UpToDate }
            }

            // Subsequent manual check after completion succeeds
            manager.checkManually()
            withTimeout(5_000L) {
                while (releaseApiCalls.get() < 2 || manager.updateState.value !is AppUpdateState.UpToDate) {
                    delay(20L)
                }
            }
            assertEquals(2, releaseApiCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scenario 7 - manual check when offline shows clear non-crashing message`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context)
        val networkCalls = AtomicInteger(0)
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.0.0" },
            httpGet = {
                networkCalls.incrementAndGet()
                200 to "{}"
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { false },
                preferencesRepository = prefs,
                isCastingActive = { false },
                scope = scope
            )

            manager.checkManually()
            val errState = withTimeout(5_000L) {
                manager.updateState.first { it is AppUpdateState.Error }
            } as AppUpdateState.Error

            assertEquals(0, networkCalls.get())
            assertEquals("No internet connection. Connect to the internet and try again.", errState.message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `scenario 20 - active Chromecast playback suppresses automatic startup check cleanly`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferencesRepository(context).apply {
            setAutoCheckUpdates(true)
            setGithubUpdateRepo("panther/lumacast")
        }
        val calls = AtomicInteger(0)
        val repo = GitHubReleaseRepository(
            currentVersionProvider = { "1.0.0" },
            httpGet = {
                calls.incrementAndGet()
                200 to releaseJson()
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = UpdateManager(
                releaseRepository = repo,
                downloadManager = UpdateDownloadManager(context),
                verificationManager = ApkVerificationManager(context),
                installationManager = ApkInstallationManager(context),
                connectivityChecker = NetworkConnectivityChecker(context) { true },
                preferencesRepository = prefs,
                isCastingActive = { true },
                scope = scope
            )

            manager.triggerStartupCheckIfEligible(startupDelayMs = 0L)
            delay(150L)

            assertEquals(0, calls.get())
            assertEquals(AppUpdateState.Idle, manager.updateState.value)
            assertFalse(manager.showUpdateDialog.value)
        } finally {
            scope.cancel()
        }
    }

    // ---- Scenarios 8, 9, 10, 11: GitHub API Errors, Manifest Parsing, Draft/Prerelease Filtering ----

    @Test
    fun `scenarios 8 9 10 - github api unavailable rate limit and malformed json fail gracefully`() = runBlocking {
        val repo500 = GitHubReleaseRepository({ "1.0.0" }) { 503 to "Service Unavailable" }
        val r500 = repo500.fetchLatestStableRelease("panther/lumacast")
        assertTrue(r500 is UpdateCheckResult.Failure)
        assertTrue((r500 as UpdateCheckResult.Failure).reason.contains("503"))

        val repo403 = GitHubReleaseRepository({ "1.0.0" }) { 403 to "API rate limit exceeded" }
        val r403 = repo403.fetchLatestStableRelease("panther/lumacast")
        assertTrue(r403 is UpdateCheckResult.Failure)
        assertTrue((r403 as UpdateCheckResult.Failure).reason.contains("rate limit", ignoreCase = true))

        val repoBadJson = GitHubReleaseRepository({ "1.0.0" }) { 200 to "{invalid-json" }
        val rBad = repoBadJson.fetchLatestStableRelease("panther/lumacast")
        assertTrue(rBad is UpdateCheckResult.Failure)
        assertTrue((rBad as UpdateCheckResult.Failure).reason.contains("Invalid GitHub release JSON"))

        val repoMissingApk = GitHubReleaseRepository({ "1.0.0" }) {
            200 to """{"tag_name":"v1.2.0","draft":false,"prerelease":false,"assets":[]}"""
        }
        val rNoApk = repoMissingApk.fetchLatestStableRelease("panther/lumacast")
        assertTrue(rNoApk is UpdateCheckResult.Failure)
        assertTrue((rNoApk as UpdateCheckResult.Failure).reason.contains("no APK download asset"))
    }

    @Test
    fun `scenario 11 - drafts and prereleases are ignored while valid manifest release enriches metadata`() = runBlocking {
        val draftObj = JSONObject(releaseJson(tag = "v9.9.9", draft = true))
        val preObj = JSONObject(releaseJson(tag = "v2.0.0-rc.1", prerelease = true))
        val directRepo = GitHubReleaseRepository({ "1.0.0" }) { 404 to "" }
        assertNull(directRepo.parseStableReleaseObject(draftObj))
        assertNull(directRepo.parseStableReleaseObject(preObj))

        val stableRepo = GitHubReleaseRepository({ "1.0.0" }) { url ->
            when {
                url.endsWith("/releases/latest") -> 200 to releaseJson(tag = "v1.2.0", draft = false, prerelease = false)
                url.endsWith("update-manifest.json") -> 200 to manifestJson(versionName = "1.2.0", versionCode = 12)
                else -> 404 to ""
            }
        }
        val result = stableRepo.fetchLatestStableRelease("panther/lumacast") as UpdateCheckResult.Success
        val latest = result.latestRelease
        assertNotNull(latest)
        assertEquals(12, latest!!.versionCode)
        assertEquals("1.2.0", latest.versionName)
        assertEquals(SAMPLE_SHA256, latest.apkAsset?.sha256Expected)
    }

    @Test
    fun `manifest parser rejects malformed fields and http URLs`() {
        val httpManifest = manifestJson(apkUrl = "http://insecure.example.com/app.apk")
        assertTrue(UpdateManifestParser.parse(httpManifest) is ManifestParseResult.Invalid)

        val badShaManifest = manifestJson(sha256 = "not-a-sha")
        assertTrue(UpdateManifestParser.parse(badShaManifest) is ManifestParseResult.Invalid)

        val zeroCodeManifest = manifestJson(versionCode = 0)
        assertTrue(UpdateManifestParser.parse(zeroCodeManifest) is ManifestParseResult.Invalid)
    }

    // ---- Version Comparator & Repo Slug Normalization ----

    @Test
    fun `version comparator prioritizes versionCode and rejects equal or downgraded builds`() {
        assertEquals(
            VersionComparator.ComparisonDecision.UpdateAvailable,
            VersionComparator.evaluateUpdate(5, "1.0.0", 6, "1.0.1")
        )
        assertEquals(
            VersionComparator.ComparisonDecision.AlreadyUpToDate,
            VersionComparator.evaluateUpdate(5, "1.0.0", 5, "1.0.0")
        )
        assertEquals(
            VersionComparator.ComparisonDecision.DowngradeRejected,
            VersionComparator.evaluateUpdate(6, "1.1.0", 5, "1.2.0")
        )
        // Secondary fallback when versionCode is absent
        assertEquals(
            VersionComparator.ComparisonDecision.UpdateAvailable,
            VersionComparator.evaluateUpdate(5, "1.0.0", null, "1.10.0")
        )
        assertEquals(
            VersionComparator.ComparisonDecision.DowngradeRejected,
            VersionComparator.evaluateUpdate(5, "1.2.0", null, "1.2.0-beta.1")
        )
        assertTrue(SemanticVersion.isNewer("v1.0.1", "1.0.0"))
        assertFalse(SemanticVersion.isNewer("v0.9.9", "1.0.0"))
    }

    @Test
    fun `github repository slug normalizes urls and ssh remotes safely`() {
        assertEquals("panther/lumacast", SemanticVersion.normalizeGitHubRepoSlug("panther/lumacast"))
        assertEquals(
            "panther/lumacast",
            SemanticVersion.normalizeGitHubRepoSlug("https://github.com/panther/lumacast")
        )
        assertEquals(
            "panther/lumacast",
            SemanticVersion.normalizeGitHubRepoSlug("https://github.com/panther/lumacast.git/")
        )
        assertEquals(
            "panther/lumacast",
            SemanticVersion.normalizeGitHubRepoSlug("git@github.com:panther/lumacast.git")
        )
        assertNull(SemanticVersion.normalizeGitHubRepoSlug("invalid-repo-only"))
        assertNull(SemanticVersion.normalizeGitHubRepoSlug("https://github.com/bad slug/repo"))
    }

    // ---- Scenarios 12, 13, 14, 15: Storage Check & Multi-Layer APK Verification ----

    @Test
    fun `scenario 12 - download manager rejects insecure URLs and insufficient device storage`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dm = UpdateDownloadManager(context) { 1024L } // Only 1 KB free

        val insecureRes = dm.downloadApk(
            GitHubApkAsset("app.apk", "http://example.com/app.apk", 500L, "application/vnd.android.package-archive")
        ) { _, _, _ -> }
        assertTrue(insecureRes is ApkDownloadResult.Failure)
        assertTrue((insecureRes as ApkDownloadResult.Failure).reason.contains("Insecure"))

        val lowSpaceRes = dm.downloadApk(
            GitHubApkAsset("app.apk", "https://example.com/app.apk", 50_000_000L, "application/vnd.android.package-archive")
        ) { _, _, _ -> }
        assertTrue(lowSpaceRes is ApkDownloadResult.Failure)
        assertTrue((lowSpaceRes as ApkDownloadResult.Failure).reason.contains("Insufficient storage"))
    }

    @Test
    fun `scenarios 13 14 15 - apk verification enforces zip header sha256 package id versionCode and signing key`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheUpdates = File(context.cacheDir, "updates").apply { mkdirs() }
        val dummyApk = File(cacheUpdates, "test-update.apk").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 9, 9))
        }
        val actualSha = ApkVerificationManager.computeSha256(dummyApk)
        val installedCert = setOf("aabbccdd11223344")

        val installedMeta = ParsedApkMetadata(context.packageName, 1L, installedCert)

        // Valid matching package, higher versionCode, matching cert, valid SHA-256
        val validVerifier = ApkVerificationManager(
            context = context,
            installedPackageInspector = { installedMeta },
            archiveInspector = { ParsedApkMetadata(context.packageName, 2L, installedCert) }
        )
        val okRes = validVerifier.verifyDownloadedApk(dummyApk, installedVersionCode = 1, expectedSha256 = actualSha)
        assertTrue(okRes is ApkVerificationResult.Verified)
        assertTrue((okRes as ApkVerificationResult.Verified).sha256Verified)

        // Scenario 13: Wrong SHA-256 rejected
        val badSha = validVerifier.verifyDownloadedApk(dummyApk, 1, expectedSha256 = SAMPLE_SHA256)
        assertTrue(badSha is ApkVerificationResult.Rejected)
        assertTrue((badSha as ApkVerificationResult.Rejected).reason.contains("SHA-256"))

        // Wrong package ID rejected
        val badPkgVerifier = ApkVerificationManager(
            context,
            { installedMeta },
            { ParsedApkMetadata("com.evil.spoof", 2L, installedCert) }
        )
        assertTrue(badPkgVerifier.verifyDownloadedApk(dummyApk, 1, actualSha) is ApkVerificationResult.Rejected)

        // Scenario 15: Version downgrade rejected
        val downgradeVerifier = ApkVerificationManager(
            context,
            { installedMeta },
            { ParsedApkMetadata(context.packageName, 1L, installedCert) }
        )
        val downgradeRes = downgradeVerifier.verifyDownloadedApk(dummyApk, 1, actualSha)
        assertTrue(downgradeRes is ApkVerificationResult.Rejected)
        assertTrue((downgradeRes as ApkVerificationResult.Rejected).reason.contains("downgrade"))

        // Scenario 14: Signature mismatch rejected
        val badSigVerifier = ApkVerificationManager(
            context,
            { installedMeta },
            { ParsedApkMetadata(context.packageName, 2L, setOf("ffffffff99999999")) }
        )
        val badSigRes = badSigVerifier.verifyDownloadedApk(dummyApk, 1, actualSha)
        assertTrue(badSigRes is ApkVerificationResult.Rejected)
        assertTrue((badSigRes as ApkVerificationResult.Rejected).reason.contains("Signing certificate mismatch"))

        // Corrupted ZIP header rejected
        val corruptFile = File(cacheUpdates, "corrupt.apk").apply { writeText("<html>not-an-apk</html>") }
        assertTrue(validVerifier.verifyDownloadedApk(corruptFile, 1, null) is ApkVerificationResult.Rejected)
    }

    // ---- Scenarios 16, 17, 18, 19: Install Permission, FileProvider, Cancellation & Cache Cleanup ----

    @Test
    fun `scenarios 16 17 18 - installation manager builds unknown sources intent fileprovider uri and result messages`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val noPermManager = ApkInstallationManager(context) { false }
        assertFalse(noPermManager.canRequestPackageInstalls())
        val permIntent = noPermManager.createManageUnknownAppSourcesIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permIntent.action)
        assertEquals("package:${context.packageName}", permIntent.dataString)

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dummyApk = File(updatesDir, "LumaCast-v1.2.0.apk").apply {
            writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4))
        }
        val intent = noPermManager.createInstallApkIntent(dummyApk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        val data: Uri? = intent.data
        assertNotNull(data)
        assertEquals("content", data?.scheme)
        assertEquals("${context.packageName}.fileprovider", data?.authority)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))

        assertNull(noPermManager.formatInstallerResultMessage(Activity.RESULT_OK))
        assertTrue(
            noPermManager.formatInstallerResultMessage(Activity.RESULT_CANCELED)!!
                .contains("cancelled", ignoreCase = true)
        )
    }

    // ---- UI Coverage: AppUpdateDialog & Settings "App Updates" section ----

    @Test
    fun `app update dialog renders version jump changelog and triggers download callback`() {
        var clickedDownload = false
        val release = GitHubReleaseInfo(
            tagName = "v1.2.0",
            versionName = "1.2.0",
            versionCode = 2,
            releaseTitle = "LumaCast v1.2.0",
            releaseNotes = "- Smooth 60fps library scrolling\n- Faster Chromecast discovery",
            publishedAtIso = "2026-11-22T10:00:00Z",
            htmlUrl = "https://github.com/panther/lumacast/releases/tag/v1.2.0",
            isPreRelease = false,
            apkAsset = GitHubApkAsset(
                name = "LumaCast-v1.2.0.apk",
                downloadUrl = "https://github.com/panther/lumacast/releases/download/v1.2.0/LumaCast-v1.2.0.apk",
                sizeBytes = 18_500_000L,
                contentType = "application/vnd.android.package-archive"
            )
        )

        composeRule.setContent {
            LumaCastTheme(darkTheme = true) {
                AppUpdateDialog(
                    updateState = AppUpdateState.UpdateAvailable(
                        release = release,
                        currentVersion = "1.0.0",
                        currentVersionCode = 1
                    ),
                    currentVersion = "1.0.0",
                    onDownloadAndInstall = { clickedDownload = true },
                    onInstallNow = {},
                    onOpenInstallPermission = {},
                    onCancelDownload = {},
                    onRetryCheck = {},
                    onSkipVersion = {},
                    onOpenReleasePage = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("app_update_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Installed: v1.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("Latest: v1.2.0").assertIsDisplayed()
        composeRule.onNodeWithTag("update_download_button").performClick()
        assertTrue(clickedDownload)
    }

    @Test
    fun `settings app updates section shows latest version status and handles check tap`() {
        var checkClicks = 0
        composeRule.setContent {
            LumaCastTheme(darkTheme = true) {
                SettingsScreen(
                    settings = UserSettings(githubUpdateRepo = "panther/lumacast", lastUpdateCheckMs = 0L),
                    folders = emptyList(),
                    cacheSizeBytes = 0L,
                    networkHealth = NetworkHealthState(),
                    connectedDeviceName = null,
                    discoveredDeviceCount = 0,
                    isScanningDevices = false,
                    appUpdateState = AppUpdateState.UpToDate(
                        checkedAtMs = 1_700_000_000_000L,
                        currentVersion = "1.0.0",
                        currentVersionCode = 1
                    ),
                    onSetReceiverProfile = {},
                    onSetCustomReceiverAppId = {},
                    onSetAutoRemux = {},
                    onSetAllowTranscode = {},
                    onSetMaxTranscodeHeight = {},
                    onSetPreferredAudioLanguage = {},
                    onSetPreferAc3Passthrough = {},
                    onSetPreferredSubtitleLanguage = {},
                    onSetPreferredPort = {},
                    onSetKeepScreenOn = {},
                    onRemoveFolder = {},
                    onClearCache = {},
                    onRefreshDevices = {},
                    onOpenHotspotSettings = {},
                    onOpenTroubleshooting = {},
                    onCheckForUpdates = { checkClicks++ }
                )
            }
        }

        composeRule.onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_check_updates_button"))
        composeRule.onNodeWithTag("settings_check_updates_button").assertIsDisplayed()
        composeRule.onNodeWithText("You’re using the latest version.").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_check_updates_button").performClick()
        assertEquals(1, checkClicks)
    }

    @Test
    fun `settings app updates button disables cleanly while checking is in progress`() {
        composeRule.setContent {
            LumaCastTheme(darkTheme = true) {
                SettingsScreen(
                    settings = UserSettings(githubUpdateRepo = "panther/lumacast"),
                    folders = emptyList(),
                    cacheSizeBytes = 0L,
                    networkHealth = NetworkHealthState(),
                    connectedDeviceName = null,
                    discoveredDeviceCount = 0,
                    isScanningDevices = false,
                    appUpdateState = AppUpdateState.Checking,
                    onSetReceiverProfile = {},
                    onSetCustomReceiverAppId = {},
                    onSetAutoRemux = {},
                    onSetAllowTranscode = {},
                    onSetMaxTranscodeHeight = {},
                    onSetPreferredAudioLanguage = {},
                    onSetPreferAc3Passthrough = {},
                    onSetPreferredSubtitleLanguage = {},
                    onSetPreferredPort = {},
                    onSetKeepScreenOn = {},
                    onRemoveFolder = {},
                    onClearCache = {},
                    onRefreshDevices = {},
                    onOpenHotspotSettings = {},
                    onOpenTroubleshooting = {}
                )
            }
        }

        composeRule.onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_check_updates_button"))
        composeRule.onNodeWithTag("settings_check_updates_button").assertIsNotEnabled()
    }

    companion object {
        private const val SAMPLE_SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
