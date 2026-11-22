package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * A downloaded update is installed only when it is this app (same package) signed by this app's own
 * key, so an update can never replace Di-Connect with a different app or a differently signed build,
 * and Android keeps the settings when it installs over the top.
 */
internal object UpdateSignature {
    fun matchesInstalled(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = installedInfo(pm, context.packageName) ?: return false
        return sameSigners(signers(archive), signers(installed))
    }

    /** SHA-256 digests of each signing certificate; equal non-empty sets mean the same key. */
    internal fun sameSigners(update: Set<String>, installed: Set<String>): Boolean =
        update.isNotEmpty() && update == installed

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(apk.path, flags)?.takeIf { signers(it).isNotEmpty() }
            ?: pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        pm.getPackageInfo(packageName, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else info.signatures
        return certificates.orEmpty().mapNotNull { it?.toByteArray() }.map(::sha256).toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
