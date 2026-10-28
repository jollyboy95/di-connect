package com.shilapi.xcertplay.update

internal object UpdateVersion {
    private val TRIPLE = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")
    /** Di-Connect preview revision, as in 0.1.0-preview.r25625761. */
    private val REVISION = Regex("[.-]r(\\d+)\\b")

    internal fun revision(value: String): Long? = REVISION.find(value)?.groupValues?.get(1)?.toLongOrNull()

    internal fun parse(value: String): Triple<Int, Int, Int>? =
        TRIPLE.find(value)?.let { match ->
            Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        if (target.first != current.first) return target.first > current.first
        if (target.second != current.second) return target.second > current.second
        if (target.third != current.third) return target.third > current.third
        // Same version: a later Di-Connect preview revision is newer; without revisions it is the same.
        val remoteRevision = revision(remote) ?: return false
        val installedRevision = revision(installed) ?: return false
        return remoteRevision > installedRevision
    }
}
