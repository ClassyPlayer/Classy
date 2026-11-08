package com.themoon.y1.migrations

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipFile

object BootScreenMigrator {

    private const val TAG = "BootScreenMigrator"
    const val ASSET_POLICY_JAR = "boot/android.policy.jar"
    const val TARGET_POLICY_JAR = "/system/framework/android.policy.jar"
    const val TARGET_POLICY_ODEX = "/system/framework/android.policy.odex"
    const val TARGET_FRAMEWORK_RES = "/system/framework/framework-res.apk"

    /**
     * Checks if /system/framework/android.policy.jar contains ClassyBootScreen.
     */
    fun isPolicyJarPatched(): Boolean {
        val file = File(TARGET_POLICY_JAR)
        if (!file.exists()) return false
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry("classes.dex") ?: return false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                // Check for ASCII bytes "ClassyBootScreen"
                val str = String(bytes, Charsets.ISO_8859_1)
                str.contains("ClassyBootScreen")
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if framework-res.apk has already been patched with Classy boot strings.
     */
    fun isFrameworkResPatched(): Boolean =
        BootStringsPatcher.isPatched(File(TARGET_FRAMEWORK_RES))

    /**
     * Checks if root (`su`) is available and executable.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Fully applies the boot screen migration on a running device using `su`.
     * Safe to call multiple times (idempotent).
     */
    fun applyMigration(context: Context): Boolean {
        val policyPatched = isPolicyJarPatched()
        val stringsPatched = isFrameworkResPatched()
        if (policyPatched && stringsPatched) {
            Log.i(TAG, "Boot screen and strings are already patched")
            return true
        }

        val cacheDir = context.cacheDir
        val tempJar = File(cacheDir, "classy_android_policy.jar")
        val tempApk = File(cacheDir, "classy_framework_res.apk")

        try {
            // 1. Extract android.policy.jar from assets if needed
            if (!policyPatched) {
                context.assets.open(ASSET_POLICY_JAR).use { input ->
                    tempJar.outputStream().use { output -> input.copyTo(output) }
                }
            }

            // 2. Copy and patch framework-res.apk if needed
            var apkNeedsWrite = false
            if (!stringsPatched && File(TARGET_FRAMEWORK_RES).exists()) {
                File(TARGET_FRAMEWORK_RES).copyTo(tempApk, overwrite = true)
                if (BootStringsPatcher.patchInPlace(tempApk)) {
                    apkNeedsWrite = true
                } else {
                    tempApk.delete()
                    Log.e(TAG, "Failed to patch framework-res.apk boot strings")
                    return false
                }
            }

            // 3. Execute root install commands with atomic replacement
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)

            os.writeBytes("set -e\n")
            os.writeBytes("trap 'rm -f $TARGET_POLICY_JAR.classy_tmp $TARGET_FRAMEWORK_RES.classy_tmp' EXIT\n")
            os.writeBytes("mount -o remount,rw /system 2>/dev/null || mount -o remount,rw /system\n")

            if (!policyPatched && tempJar.exists()) {
                val p = tempJar.absolutePath
                val stagedJar = "$TARGET_POLICY_JAR.classy_tmp"
                os.writeBytes("cp $p $stagedJar\n")
                os.writeBytes("chmod 644 $stagedJar\n")
                os.writeBytes("chown root:root $stagedJar\n")
                os.writeBytes("mv -f $stagedJar $TARGET_POLICY_JAR\n")
                os.writeBytes("rm -f $TARGET_POLICY_ODEX\n")
            }

            if (apkNeedsWrite && tempApk.exists()) {
                val p = tempApk.absolutePath
                val stagedApk = "$TARGET_FRAMEWORK_RES.classy_tmp"
                os.writeBytes("cp $p $stagedApk\n")
                os.writeBytes("chmod 644 $stagedApk\n")
                os.writeBytes("chown root:root $stagedApk\n")
                os.writeBytes("mv -f $stagedApk $TARGET_FRAMEWORK_RES\n")
            }

            os.writeBytes("trap - EXIT\n")
            os.writeBytes("sync\n")
            os.writeBytes("exit 0\n")
            os.flush()
            os.close()

            val exitCode = process.waitFor()
            Log.i(TAG, "Boot screen migration su exited with $exitCode")
            return exitCode == 0
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply boot screen migration", e)
            return false
        } finally {
            if (tempJar.exists()) tempJar.delete()
            if (tempApk.exists()) tempApk.delete()
        }
    }
}
