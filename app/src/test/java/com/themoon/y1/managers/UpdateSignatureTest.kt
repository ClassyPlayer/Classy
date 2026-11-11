package com.themoon.y1.managers

import com.themoon.y1.managers.UpdateSignature.Verdict
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class UpdateSignatureTest {

    private val release = setOf(UpdateSignature.RELEASE_CERT_SHA256)
    private val other = setOf("0".repeat(64))

    @Test
    fun releaseKey_installsOverTheFirmwareCopy() {
        assertEquals(Verdict.INSTALL, UpdateSignature.decide(release, other, onlyFirmwareCopy = true))
    }

    @Test
    fun releaseKey_cannotReplaceACopyInDataAppWithAnotherKey() {
        assertEquals(Verdict.CANNOT_REPLACE, UpdateSignature.decide(release, other, onlyFirmwareCopy = false))
        assertEquals(Verdict.CANNOT_REPLACE, UpdateSignature.decide(release, emptySet(), onlyFirmwareCopy = false))
    }

    @Test
    fun installedAppsOwnKey_installs() {
        assertEquals(Verdict.INSTALL, UpdateSignature.decide(other, other, onlyFirmwareCopy = false))
        assertEquals(Verdict.INSTALL, UpdateSignature.decide(release, release, onlyFirmwareCopy = false))
    }

    @Test
    fun releaseKey_installsWhenTheInstalledAppIsUnknown() {
        assertEquals(Verdict.INSTALL, UpdateSignature.decide(release, null, onlyFirmwareCopy = false))
    }

    @Test
    fun unknownKey_neverInstalls_alsoNotOverTheFirmwareCopy() {
        val unknown = setOf("1".repeat(64))
        assertEquals(Verdict.UNVERIFIED, UpdateSignature.decide(unknown, other, onlyFirmwareCopy = true))
        assertEquals(Verdict.UNVERIFIED, UpdateSignature.decide(unknown, null, onlyFirmwareCopy = false))
        // The release key and one more signer is not the release key
        assertEquals(Verdict.UNVERIFIED, UpdateSignature.decide(release + unknown, other, onlyFirmwareCopy = true))
    }

    @Test
    fun notVerified_neverInstalls() {
        assertEquals(Verdict.UNVERIFIED, UpdateSignature.decide(null, other, onlyFirmwareCopy = true))
        assertEquals(Verdict.UNVERIFIED, UpdateSignature.decide(emptySet(), emptySet(), onlyFirmwareCopy = false))
    }

    @Test
    fun sha256Hex_isLowercaseHexOfTheBytes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateSignature.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun signedApk_givesItsSigner() {
        assertEquals(setOf(certA), UpdateSignature.verifiedSigners(signedA))
    }

    @Test
    fun apkSignedWithTwoKeys_givesBothSigners() {
        assertEquals(setOf(certA, certB), UpdateSignature.verifiedSigners(signedAB))
    }

    @Test
    fun unsignedApk_isRejected() {
        assertNull(UpdateSignature.verifiedSigners(unsigned))
    }

    @Test
    fun copyWithoutChanges_stillVerifies() {
        // The rewrite of the tests below changes only what each test changes
        assertEquals(setOf(certA), UpdateSignature.verifiedSigners(rewrite(signedA, "copy.apk")))
    }

    @Test
    fun changedByteInALibrary_isRejected() {
        val changed = rewrite(signedA, "changed.apk") { name, data ->
            if (name == LIB) data.also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } else data
        }
        assertNull(UpdateSignature.verifiedSigners(changed))
    }

    @Test
    fun changedByteInAnAsset_isRejected() {
        val changed = rewrite(signedA, "changed-asset.apk") { name, data ->
            if (name == POLICY_JAR) data.also { it[0] = (it[0] + 1).toByte() } else data
        }
        assertNull(UpdateSignature.verifiedSigners(changed))
    }

    @Test
    fun extraUnsignedEntry_isRejected() {
        assertNull(UpdateSignature.verifiedSigners(rewrite(signedA, "extra.apk", extra = "lib/armeabi-v7a/libevil.so")))
    }

    @Test
    fun extraUnsignedEntryInMetaInf_isRejected() {
        assertNull(UpdateSignature.verifiedSigners(rewrite(signedA, "extra-meta.apk", extra = "META-INF/services/evil")))
    }

    @Test
    fun removedSignedEntry_isRejected() {
        // The signature files stay, but AndroidManifest.xml is gone
        assertNull(UpdateSignature.verifiedSigners(rewrite(signedA, "no-manifest.apk", drop = "AndroidManifest.xml")))
    }

    @Test
    fun entryThatOnlyOneOfTwoSignersSigned_isRejected() {
        // Signed with A, then an entry added and the whole APK signed with B: the new entry has only B
        val withExtra = rewrite(signedA, "a-extra.apk", extra = "assets/extra.bin")
        val mixed = File(dir, "mixed.apk")
        sign(withExtra, mixed, "b")
        assertNull(UpdateSignature.verifiedSigners(mixed))
    }

    @Test
    fun twoEntriesWithOneName_areRejected() {
        // "classes.dey" becomes a second "classes.dex": a reader that takes the second one would get unchecked bytes
        val bytes = rewrite(signedA, "dup-source.apk", extra = "classes.dey").readBytes()
        val from = "classes.dey".toByteArray()
        val to = "classes.dex".toByteArray()
        var i = 0
        while (i <= bytes.size - from.size) {
            if (from.indices.all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
            i++
        }
        val duplicate = File(dir, "duplicate.apk").apply { writeBytes(bytes) }
        assertNull(UpdateSignature.verifiedSigners(duplicate))
    }

    @Test
    fun missingOrBrokenFile_isRejected() {
        assertNull(UpdateSignature.verifiedSigners(File(dir, "missing.apk")))
        assertNull(UpdateSignature.verifiedSigners(File(dir, "broken.apk").apply { writeText("not a zip") }))
    }

    /** Copies [src] entry by entry (with [change] applied to the data, without [drop]), and adds an unsigned entry [extra]. */
    private fun rewrite(
        src: File,
        name: String,
        extra: String? = null,
        drop: String? = null,
        change: (String, ByteArray) -> ByteArray = { _, data -> data }
    ): File {
        val out = File(dir, name)
        ZipFile(src).use { zip ->
            ZipOutputStream(out.outputStream()).use { zos ->
                for (entry in zip.entries()) {
                    if (entry.name == drop) continue
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(change(entry.name, data))
                    zos.closeEntry()
                }
                if (extra != null) {
                    zos.putNextEntry(ZipEntry(extra))
                    zos.write(ByteArray(64) { 7 })
                    zos.closeEntry()
                }
            }
        }
        return out
    }

    companion object {
        private const val LIB = "lib/armeabi-v7a/libtest.so"
        private const val POLICY_JAR = "assets/boot/android.policy.jar"
        private const val PASSWORD = "update-test"

        private lateinit var dir: File
        private lateinit var unsigned: File
        private lateinit var signedA: File
        private lateinit var signedAB: File
        private lateinit var certA: String
        private lateinit var certB: String

        private val jdkBin = File(System.getProperty("java.home"), "bin")

        /** Two test keys and test APKs, made with the keytool and jarsigner of the JDK that runs the tests. */
        @BeforeClass
        @JvmStatic
        fun makeApks() {
            assumeTrue("needs keytool and jarsigner", File(jdkBin, "keytool").exists() && File(jdkBin, "jarsigner").exists())
            dir = kotlin.io.path.createTempDirectory("update-signature").toFile()
            for (alias in listOf("a", "b")) {
                run(
                    "keytool", "-genkeypair", "-keystore", keystore(alias).path, "-storetype", "PKCS12",
                    "-storepass", PASSWORD, "-keypass", PASSWORD, "-alias", alias, "-keyalg", "EC",
                    "-groupname", "secp256r1", "-dname", "CN=Test $alias", "-validity", "2", "-noprompt"
                )
            }
            certA = certOf("a")
            certB = certOf("b")

            unsigned = File(dir, "unsigned.apk")
            ZipOutputStream(unsigned.outputStream()).use { zos ->
                val entries = linkedMapOf(
                    "AndroidManifest.xml" to ByteArray(300) { it.toByte() },
                    "classes.dex" to ByteArray(2000) { (it * 7).toByte() },
                    LIB to ByteArray(4000) { (it * 13).toByte() },
                    POLICY_JAR to ByteArray(1000) { (it * 3).toByte() },
                    "META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler" to "x.Y\n".toByteArray()
                )
                for ((name, data) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            signedA = File(dir, "signed-a.apk")
            sign(unsigned, signedA, "a")
            signedAB = File(dir, "signed-ab.apk")
            sign(signedA, signedAB, "b")
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (::dir.isInitialized) dir.deleteRecursively()
        }

        private fun keystore(alias: String) = File(dir, "$alias.p12")

        private fun certOf(alias: String): String {
            val ks = KeyStore.getInstance("PKCS12")
            FileInputStream(keystore(alias)).use { ks.load(it, PASSWORD.toCharArray()) }
            return UpdateSignature.sha256Hex(ks.getCertificate(alias).encoded)
        }

        private fun sign(src: File, out: File, alias: String) = run(
            "jarsigner", "-keystore", keystore(alias).path, "-storepass", PASSWORD, "-digestalg", "SHA-256",
            "-signedjar", out.path, src.path, alias
        )

        private fun run(tool: String, vararg args: String) {
            val process = ProcessBuilder(listOf(File(jdkBin, tool).path) + args).redirectErrorStream(true).start()
            val output = ByteArrayOutputStream()
            process.inputStream.copyTo(output)
            check(process.waitFor() == 0) { "$tool failed: $output" }
        }
    }
}
