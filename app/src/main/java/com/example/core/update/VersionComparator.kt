package com.example.core.update

/**
 * Compares installed application version (`versionCode` primary, `SemanticVersion` secondary fallback)
 * against candidate release metadata, enforcing strict monotonic upgrades and rejecting downgrades.
 */
object VersionComparator {

    sealed interface ComparisonDecision {
        data object UpdateAvailable : ComparisonDecision
        data object AlreadyUpToDate : ComparisonDecision
        data object DowngradeRejected : ComparisonDecision
        data class MinimumVersionNotMet(val minimumRequired: Int) : ComparisonDecision
        data object InvalidMetadata : ComparisonDecision
    }

    fun evaluateUpdate(
        installedVersionCode: Int,
        installedVersionName: String,
        candidateVersionCode: Int?,
        candidateVersionName: String,
        minimumSupportedVersion: Int = 1
    ): ComparisonDecision {
        if (installedVersionCode < minimumSupportedVersion) {
            // Still eligible to upgrade if candidate is newer; minimumSupportedVersion informs UI/enforcement
        }

        if (candidateVersionCode != null) {
            if (candidateVersionCode <= 0) return ComparisonDecision.InvalidMetadata
            return when {
                candidateVersionCode > installedVersionCode -> ComparisonDecision.UpdateAvailable
                candidateVersionCode == installedVersionCode -> ComparisonDecision.AlreadyUpToDate
                else -> ComparisonDecision.DowngradeRejected
            }
        }

        // Secondary fallback only if a release omitted manifest versionCode: strict SemVer comparison (never lexical string sort)
        val candidateSem = SemanticVersion.parse(candidateVersionName)
            ?: return ComparisonDecision.InvalidMetadata
        val installedSem = SemanticVersion.parse(installedVersionName)
            ?: return ComparisonDecision.InvalidMetadata

        return when {
            candidateSem > installedSem -> ComparisonDecision.UpdateAvailable
            candidateSem == installedSem -> ComparisonDecision.AlreadyUpToDate
            else -> ComparisonDecision.DowngradeRejected
        }
    }

    fun isUpdateAvailable(
        installedVersionCode: Int,
        installedVersionName: String,
        release: GitHubReleaseInfo
    ): Boolean {
        return evaluateUpdate(
            installedVersionCode = installedVersionCode,
            installedVersionName = installedVersionName,
            candidateVersionCode = release.versionCode,
            candidateVersionName = release.versionName,
            minimumSupportedVersion = release.minimumSupportedVersion
        ) == ComparisonDecision.UpdateAvailable
    }

    fun normalizeGitHubRepoSlug(input: String): String? =
        SemanticVersion.normalizeGitHubRepoSlug(input)
}

/**
 * Pure Kotlin semantic version parser & comparator supporting tags such as
 * "v1.2.3", "1.2.3", "1.2.3-beta.1", "v2.0", etc.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        val aPre = preRelease?.takeIf { it.isNotBlank() }
        val bPre = other.preRelease?.takeIf { it.isNotBlank() }
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1 // Stable release > pre-release
            bPre == null -> -1
            else -> comparePreRelease(aPre, bPre)
        }
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val aParts = a.split('.', '-')
        val bParts = b.split('.', '-')
        val count = maxOf(aParts.size, bParts.size)
        for (i in 0 until count) {
            if (i >= aParts.size) return -1
            if (i >= bParts.size) return 1
            val partA = aParts[i]
            val partB = bParts[i]
            val numA = partA.toIntOrNull()
            val numB = partB.toIntOrNull()
            val cmp = when {
                numA != null && numB != null -> numA.compareTo(numB)
                numA != null -> -1
                numB != null -> 1
                else -> partA.compareTo(partB, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    companion object {
        private val SEMVER_REGEX = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:[-+](.+))?$""")
        private val GITHUB_SLUG_REGEX = Regex("""^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$""")

        fun parse(raw: String): SemanticVersion? {
            val cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            val match = SEMVER_REGEX.matchEntire(cleaned) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: 0
            val patch = match.groupValues[3].toIntOrNull() ?: 0
            val suffix = match.groupValues[4].takeIf { it.isNotBlank() }
                ?.substringBefore('+')
            return SemanticVersion(major, minor, patch, suffix)
        }

        fun isNewer(candidateRaw: String, currentRaw: String): Boolean {
            val candidate = parse(candidateRaw) ?: return false
            val current = parse(currentRaw) ?: return false
            return candidate > current
        }

        fun normalizeGitHubRepoSlug(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null

            val candidate = when {
                trimmed.startsWith("git@github.com:", ignoreCase = true) ->
                    trimmed.substringAfter(':')
                trimmed.contains("github.com/", ignoreCase = true) ->
                    trimmed.substringAfter("github.com/", "")
                else -> trimmed
            }
                .trim('/')
                .removeSuffix(".git")
                .trim('/')
                .split('/')
                .take(2)
                .joinToString("/")

            return candidate.takeIf { GITHUB_SLUG_REGEX.matches(it) }
        }
    }
}
