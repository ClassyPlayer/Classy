package com.themoon.y1.managers

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile

/**
 * Checks a downloaded update before System Update changes anything. The install script copies the APK over the
 * firmware copy (/system/priv-app) and copies files from it into /system, and Android checks a firmware app only at
 * its AndroidManifest.xml (PackageParser.collectCertificates with PARSE_IS_SYSTEM): so each entry is checked here.
 */
object UpdateSignature {

    /** SHA-256 of the certificate (CN=Classy, O=ClassyPlayer) of the release key, which signs each release since 20261111. */
    const val RELEASE_CERT_SHA256 = "addbf52df5c9b0b41d710ca1928cb90a9b7d3b59b7921c03695480710cbe34bd"

    enum class Verdict {
        /** Signed with a trusted key, and the update can replace the installed app. */
        INSTALL,
        /** Not signed, changed after signing, or signed with an unknown key: nothing may change. */
        UNVERIFIED,
        /** Signed with the release key, but Android cannot replace the installed copy (a copy in /data/app with another key). */
        CANNOT_REPLACE
    }

    /**
     * [apkSigners]: from [verifiedSigners]. [installedSigners]: the installed app's, or null when unknown.
     * The update must have the installed app's key, or the release key. Android replaces the app only with the same
     * key; with another key, only the firmware copy (/system) takes the update, at the next start, and only when no
     * copy in /data/app hides it ([onlyFirmwareCopy]).
     */
    fun decide(apkSigners: Set<String>?, installedSigners: Set<String>?, onlyFirmwareCopy: Boolean): Verdict {
        if (apkSigners.isNullOrEmpty()) return Verdict.UNVERIFIED
        val sameKey = apkSigners == installedSigners
        if (!sameKey && apkSigners != setOf(RELEASE_CERT_SHA256)) return Verdict.UNVERIFIED
        return if (sameKey || onlyFirmwareCopy || installedSigners == null) Verdict.INSTALL else Verdict.CANNOT_REPLACE
    }

    /**
     * The signers of [apk] (SHA-256 of each certificate), when its v1 (JAR) signature covers each entry, each entry
     * matches its digest, and all entries have the same signers. Else null. Like PackageParser.collectCertificates for
     * an app that is not in the firmware, but also for the META-INF entries that are not signature files.
     */
    fun verifiedSigners(apk: File): Set<String>? = try {
        JarFile(apk, true).use { jar -> signersOf(jar) }
    } catch (_: Exception) {
        // IOException, or SecurityException: an entry or the manifest does not match the signature
        null
    }

    /** [verifiedSigners] for an open, verifying [jar]. */
    private fun signersOf(jar: JarFile): Set<String>? {
        var signers: Set<String>? = null
        val names = HashSet<String>()
        val buffer = ByteArray(64 * 1024)
        for (entry in jar.entries()) {
            // Two entries with one name: a reader can get the one that was not checked (KitKat's ZipFile refuses them too)
            if (!names.add(entry.name)) return null
            if (entry.isDirectory || isSignatureFile(entry.name)) continue
            // The digest is checked, and the certificates are known, only after all of the entry was read
            jar.getInputStream(entry).use { input -> while (input.read(buffer) >= 0) { /* to the end */ } }
            val certs = entry.certificates ?: return null
            val entrySigners = certs.map { sha256Hex(it.encoded) }.toSet()
            if (entrySigners.isEmpty()) return null
            if (signers == null) signers = entrySigners else if (signers != entrySigners) return null
        }
        // Each entry that the signed manifest lists must be there: an APK without its AndroidManifest.xml, for example,
        // would replace the firmware copy with an app that Android cannot start
        val listed = jar.manifest?.entries?.keys ?: return null
        if (!names.containsAll(listed)) return null
        return signers
    }

    /** The manifest and the signature files in META-INF/, which the signature does not list (as KitKat's JarFile finds them). */
    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val file = name.substring("META-INF/".length).uppercase(Locale.US)
        if (file.contains('/')) return false
        return file == "MANIFEST.MF" || listOf(".SF", ".RSA", ".DSA", ".EC").any { file.endsWith(it) }
    }

    /** SHA-256 of a certificate's DER bytes (Signature.toByteArray, Certificate.getEncoded), in lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
