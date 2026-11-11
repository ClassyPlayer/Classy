package com.themoon.y1.managers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.themoon.y1.StoragePaths
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.views.Notice
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Manages GitHub OTA release checking, version comparison, download progress,
 * and silent root installation for the Innioasis Y2.
 */
class OtaUpdateManager(
    private val activity: Activity,
    private val clickFeedback: () -> Unit,
    private val onRequireWifi: () -> Unit,
    private val localize: (String) -> String
) {

    companion object {
        /** A library name the update may write into /system/lib (no path, no shell characters). */
        private val NATIVE_LIB_NAME = Regex("lib[A-Za-z0-9_.+-]+\\.so")

        /** The exit code of the install script when the native libraries could not be installed (nothing changed). */
        private const val LIBS_FAILED_EXIT = 3

        /** True from the start of a download to its install or its end: one at a time, as all write the same file. */
        private val downloadRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        // Read from the release asset rather than a file on main: the workflow publishes
        // dist/update.json with every release, so this URL needs nothing committed to the repo.
        const val UPDATE_JSON_URL = "https://github.com/ClassyPlayer/Classy/releases/latest/download/update.json"

        fun extractBuildNumber(suffix: String?): Int {
            if (suffix.isNullOrEmpty()) return 0
            val m = Pattern.compile("(\\d+)").matcher(suffix)
            if (m.find()) {
                return try {
                    m.group(1).toInt()
                } catch (_: Exception) {
                    0
                }
            }
            return 0
        }

        fun compareVersionStrings(s1: String?, s2: String?): Int {
            var v1 = s1?.trim() ?: ""
            var v2 = s2?.trim() ?: ""
            if (v1.startsWith("v", ignoreCase = true)) v1 = v1.substring(1)
            if (v2.startsWith("v", ignoreCase = true)) v2 = v2.substring(1)
            if (v1 == v2) return 0

            val datePattern = Pattern.compile("^(\\d{8})(.*)$")
            val m1 = datePattern.matcher(v1)
            val m2 = datePattern.matcher(v2)

            if (m1.matches() && m2.matches()) {
                val d1 = m1.group(1).toLong()
                val d2 = m2.group(1).toLong()
                if (d1 != d2) return if (d1 > d2) 1 else -1

                val b1 = extractBuildNumber(m1.group(2))
                val b2 = extractBuildNumber(m2.group(2))
                if (b1 != b2) return if (b1 > b2) 1 else -1
                return 0
            }

            if (m1.matches() && !m2.matches()) return 1
            if (!m1.matches() && m2.matches()) return -1

            val parts1 = v1.split("[.\\-_]".toRegex())
            val parts2 = v2.split("[.\\-_]".toRegex())
            val len = minOf(parts1.size, parts2.size)

            for (i in 0 until len) {
                val p1 = parts1[i]
                val p2 = parts2[i]
                val isNum1 = p1.matches("\\d+".toRegex())
                val isNum2 = p2.matches("\\d+".toRegex())

                if (isNum1 && isNum2) {
                    val n1 = p1.toLong()
                    val n2 = p2.toLong()
                    if (n1 != n2) return if (n1 > n2) 1 else -1
                } else {
                    val cmp = p1.compareTo(p2, ignoreCase = true)
                    if (cmp != 0) return if (cmp > 0) 1 else -1
                }
            }
            return parts1.size.compareTo(parts2.size)
        }

        fun isServerVersionNewer(serverCode: Int, serverName: String, myCode: Int, myName: String): Boolean {
            val nameCompare = compareVersionStrings(serverName, myName)
            if (nameCompare > 0) return true
            if (nameCompare < 0) return false

            var normServer = serverCode.toLong()
            var normMy = myCode.toLong()
            if (normServer > 1000000000L && normMy < 100000000L) {
                normServer /= 100
            } else if (normMy > 1000000000L && normServer < 100000000L) {
                normMy /= 100
            }
            return normServer > normMy
        }

        private fun getUpdateHttpClient(): OkHttpClient {
            return try {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS", "Conscrypt")
                sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())

                OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.socketFactory, trustAll)
                    .hostnameVerifier { _, _ -> true }
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            } catch (_: Exception) {
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val net = cm.activeNetworkInfo ?: return false
        return net.isConnected
    }

    /** System Update → Check for Updates: a progress screen, then the result screen. Back cancels. */
    fun checkForUpdates() {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val key = ActionScreens.open(ActionScreens.Spec(
            localize("System Update"),
            listOf(localize("Connecting to Wi-Fi...")),
            onCancel = { cancelled.set(true) }
        ))

        Thread {
            try {
                val wm = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                var connected = isNetworkConnected()

                if (!connected) {
                    if (wm != null && !wm.isWifiEnabled) {
                        wm.isWifiEnabled = true
                    }

                    var waitCount = 0
                    while (!connected && waitCount < 20) {
                        if (cancelled.get()) return@Thread
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        waitCount++
                        connected = isNetworkConnected()
                    }
                }

                if (cancelled.get()) return@Thread

                if (!connected) {
                    activity.runOnUiThread {
                        ActionScreens.close(key)
                        Notice.error(
                            activity,
                            localize("Wi-Fi connection required. Please connect.")
                        )
                        onRequireWifi()
                    }
                    return@Thread
                }

                ActionScreens.update(key, listOf(localize("Checking for updates...")))

                val client = getUpdateHttpClient()
                val request = Request.Builder()
                    .url(UPDATE_JSON_URL)
                    .header("User-Agent", "Classy-OTA")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code()}")
                }

                val body = response.body()?.string() ?: ""
                val root = JSONObject(body)

                val serverVersionCode = root.optInt("versionCode", 0)
                val serverVersionName = root.optString("versionName", "")
                var apkUrl = root.optString("apkUrl", "")
                if (apkUrl.isEmpty()) {
                    val apkName = root.optString("apkName", root.optString("outputFile", "Classy.apk"))
                    apkUrl = "https://github.com/ClassyPlayer/Classy/releases/latest/download/$apkName"
                }

                var myVersionName = "1.0"
                var myVersionCode = 1
                try {
                    val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
                    myVersionName = pInfo.versionName ?: "1.0"
                    myVersionCode = pInfo.versionCode
                } catch (_: Exception) {}

                val finalApkUrl = apkUrl
                val hasUpdate = isServerVersionNewer(serverVersionCode, serverVersionName, myVersionCode, myVersionName)

                activity.runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    ActionScreens.close(key)
                    if (hasUpdate) {
                        showNewVersionDialog(serverVersionName, myVersionName, finalApkUrl)
                    } else {
                        showUpToDateDialog(serverVersionName, myVersionName, finalApkUrl)
                    }
                }
            } catch (e: Throwable) {
                activity.runOnUiThread {
                    if (cancelled.get()) return@runOnUiThread
                    ActionScreens.close(key)
                    showUpdateErrorDialog(e.message ?: "Network error")
                }
            }
        }.start()
    }

    private fun showNewVersionDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        ActionScreens.open(ActionScreens.Spec(
            localize("New Version Available"),
            listOf("${localize("New version")}: $serverVersion", "(${localize("Current")}: $currentVersion)"),
            listOf(ActionScreens.Action(localize("Update Now")) { downloadAndInstallApk(apkUrl) })
        ))
    }

    private fun showUpToDateDialog(serverVersion: String, currentVersion: String, apkUrl: String) {
        val curVerStr = if (currentVersion.startsWith("v")) currentVersion else "v$currentVersion"
        // No row selected at the start (before, OK had the focus): one centre push does not download again
        ActionScreens.open(ActionScreens.Spec(
            localize("Up to Date"),
            listOf(curVerStr, localize("You are using the latest version.")),
            listOf(ActionScreens.Action(localize("Reinstall")) { downloadAndInstallApk(apkUrl) }),
            selectAction = false
        ))
    }

    @Suppress("UNUSED_PARAMETER")
    private fun showUpdateErrorDialog(errorMsg: String) {
        ActionScreens.open(ActionScreens.Spec(
            localize("Update Check Failed"),
            listOf(localize("Unable to check for updates. Check internet connection."))
        ))
    }

    /** Downloads the update on a progress screen, then installs it. Back stops the download. */
    fun downloadAndInstallApk(apkUrl: String) {
        // A download that Back stopped can still wait for its server (read timeout 60 s)
        if (!downloadRunning.compareAndSet(false, true)) {
            Notice.show(activity, localize("Please wait a moment..."))
            return
        }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val call = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
        val title = localize("Downloading Update")
        val key = ActionScreens.open(ActionScreens.Spec(
            title,
            listOf(localize("Connecting to server...")),
            onCancel = {
                cancelled.set(true)
                // Also a read that waits for the server stops now
                call.get()?.cancel()
            }
        ))

        Thread {
            var updateFile: File? = null
            var installs = false
            try {
                val client = getUpdateHttpClient()
                val request = Request.Builder()
                    .url(apkUrl)
                    .header("User-Agent", "Classy-OTA")
                    .header("Accept-Encoding", "identity")
                    .build()

                val newCall = client.newCall(request)
                call.set(newCall)
                if (cancelled.get()) newCall.cancel()
                val response = newCall.execute()
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code()}")
                }

                val responseBody = response.body() ?: throw Exception("Empty response body")
                val fileLength = responseBody.contentLength()

                val dir = activity.getDir("update", Context.MODE_PRIVATE)
                val file = File(dir, "Classy_Update.apk")
                updateFile = file

                val fos = FileOutputStream(file)
                val isStream = responseBody.byteStream()
                val buffer = ByteArray(8192)
                var len: Int
                var total: Long = 0
                var shownProgress = -1

                try {
                    while (isStream.read(buffer).also { len = it } != -1) {
                        if (cancelled.get()) throw InterruptedException("cancelled")
                        total += len.toLong()
                        fos.write(buffer, 0, len)

                        if (fileLength > 0) {
                            val progress = (total * 100 / fileLength).toInt()
                            if (progress != shownProgress) {
                                shownProgress = progress
                                val downloadedMB = total / (1024 * 1024)
                                val totalMB = fileLength / (1024 * 1024)
                                ActionScreens.update(key, listOf("$progress%   (${downloadedMB}MB / ${totalMB}MB)"))
                            }
                        }
                    }
                } finally {
                    fos.close()
                    isStream.close()
                }

                if (fileLength > 0 && total != fileLength) {
                    throw Exception("Incomplete download")
                }

                ActionScreens.update(key, listOf(localize("Download Finished! Installing...")))
                installs = true
                Handler(Looper.getMainLooper()).postDelayed({
                    ActionScreens.close(key)
                    // Back in this last second also stops the update: no install and no restart
                    if (cancelled.get()) {
                        file.delete()
                        downloadRunning.set(false)
                        return@postDelayed
                    }
                    Thread {
                        installApk(file)
                        downloadRunning.set(false)
                    }.start()
                }, 1000)
            } catch (_: Exception) {
                updateFile?.let { if (it.exists()) it.delete() }
                activity.runOnUiThread {
                    ActionScreens.close(key)
                    if (!cancelled.get()) {
                        Notice.error(
                            activity,
                            localize("Download failed. Check your internet connection.")
                        )
                    }
                }
            } finally {
                if (!installs) downloadRunning.set(false)
            }
        }.start()
    }

    /**
     * Checks the whole update and its signing key ([UpdateSignature.decide]). Without the key check the install script
     * replaced the firmware copy, the start found no usable app, and the player stayed on the start screen.
     */
    private fun updateVerdict(apkFile: File): UpdateSignature.Verdict {
        val installed = try {
            activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        } catch (_: Exception) {
            null
        }
        val flags = installed?.applicationInfo?.flags ?: 0
        val onlyFirmwareCopy = flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        val installedSigners = installed?.let { info ->
            info.signatures?.map { UpdateSignature.sha256Hex(it.toByteArray()) }?.toSet() ?: emptySet()
        }
        return UpdateSignature.decide(UpdateSignature.verifiedSigners(apkFile), installedSigners, onlyFirmwareCopy)
    }

    /** Says that the download is not a verified update, and what the user can do (nothing changed). */
    private fun showUnverifiedDialog() {
        ActionScreens.open(ActionScreens.Spec(
            localize("Cannot Verify Update"),
            listOf(
                localize("The downloaded update could not be verified, so nothing was changed."),
                localize("Try again later or on another Wi-Fi network, or install the new firmware with Innioasis Updater CE (innioasis.app).")
            )
        ))
    }

    /** Says why the update cannot install and how the user can update (verdict CANNOT_REPLACE). */
    private fun showCannotUpdateDialog() {
        ActionScreens.open(ActionScreens.Spec(
            localize("Cannot Update"),
            listOf(
                localize("This Classy was not installed with the official firmware, so the update cannot replace it."),
                localize("To update, install the new firmware with Innioasis Updater CE (innioasis.app).")
            )
        ))
    }

    /**
     * The native libraries of [apkFile] for this CPU that /system/lib does not have, or has with other content,
     * copied to the cache: (staged file, library name). Only plain names (lib/<abi>/libx.so).
     */
    private fun stageNativeLibs(apkFile: File): List<Pair<File, String>> {
        val staged = ArrayList<Pair<File, String>>()
        try {
            stageInto(apkFile, staged)
        } catch (t: Throwable) {
            staged.forEach { it.first.delete() }
            throw t
        }
        return staged
    }

    private fun stageInto(apkFile: File, staged: MutableList<Pair<File, String>>) {
        // ZipFile is Closeable only from API 19: close it in finally
        val zip = java.util.zip.ZipFile(apkFile)
        try {
            val abi = listOf(android.os.Build.CPU_ABI, android.os.Build.CPU_ABI2)
                .firstOrNull { a -> a.isNotEmpty() && zip.entries().asSequence().any { it.name.startsWith("lib/$a/") } }
                ?: return
            for (entry in zip.entries()) {
                val name = entry.name.removePrefix("lib/$abi/")
                if (entry.isDirectory || name == entry.name || !NATIVE_LIB_NAME.matches(name)) continue
                val installed = File("/system/lib", name)
                if (installed.isFile && installed.length() == entry.size && crc32(installed) == entry.crc) continue
                val out = File(activity.cacheDir, "ota_$name")
                // In the list first: a copy that fails halfway is deleted too
                staged += out to name
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        } finally {
            zip.close()
        }
    }

    private fun crc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }

    fun installApk(apkFile: File) {
        // Before any change: the script below copies the APK, and files from it, into /system as root
        val verdict = updateVerdict(apkFile)
        if (verdict != UpdateSignature.Verdict.INSTALL) {
            apkFile.delete()
            activity.runOnUiThread {
                if (verdict == UpdateSignature.Verdict.UNVERIFIED) showUnverifiedDialog() else showCannotUpdateDialog()
            }
            return
        }
        // KitKat does not unpack the libraries of an app in /system: they must be in /system/lib (as build-rom.sh
        // does). Libraries that cannot be staged stop the update before any change: the new app would miss them.
        val newLibs = try {
            stageNativeLibs(apkFile)
        } catch (_: Throwable) {
            apkFile.delete()
            activity.runOnUiThread { Notice.error(activity, localize("Install Failed.")) }
            return
        }
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)

            val apkPath = apkFile.absolutePath
            val logPath = StoragePaths.primaryFile("y1_update_log.txt").absolutePath

            // Migrate boot screen (android.policy.jar + boot strings) for OTA update before reboot (Y2 with Android 4.4 only)
            var tempJarPath: String? = null
            var tempApkPath: String? = null
            if (com.themoon.y1.migrations.BootScreenMigrator.isSupported()) try {
                if (!com.themoon.y1.migrations.BootScreenMigrator.isPolicyJarPatched()) {
                    com.themoon.y1.migrations.BootScreenMigrator.readZipEntry(
                        apkFile, "assets/" + com.themoon.y1.migrations.BootScreenMigrator.ASSET_POLICY_JAR
                    )?.let { bytes ->
                        val tempJar = File(activity.cacheDir, "ota_android_policy.jar")
                        tempJar.writeBytes(bytes)
                        tempJarPath = tempJar.absolutePath
                    }
                }
                if (!com.themoon.y1.migrations.BootScreenMigrator.isFrameworkResPatched() &&
                    File(com.themoon.y1.migrations.BootScreenMigrator.TARGET_FRAMEWORK_RES).exists()
                ) {
                    val tempApk = File(activity.cacheDir, "ota_framework_res.apk")
                    File(com.themoon.y1.migrations.BootScreenMigrator.TARGET_FRAMEWORK_RES).copyTo(tempApk, overwrite = true)
                    if (com.themoon.y1.migrations.BootStringsPatcher.patchInPlace(tempApk)) {
                        tempApkPath = tempApk.absolutePath
                    } else {
                        tempApk.delete()
                    }
                }
            } catch (_: Throwable) {}

            os.writeBytes("chmod 777 " + apkFile.parentFile.absolutePath + "\n")
            os.writeBytes("chmod 777 $apkPath\n")
            os.writeBytes("mount -o remount,rw /system 2>/dev/null\n")
            // The libraries first, in two steps: all copies (the step that fails when /system is full), then the mv of
            // each. Only when they are all in does the update replace the app; else nothing else changes and no reboot.
            os.writeBytes("ok=1\n")
            if (newLibs.isNotEmpty()) {
                os.writeBytes("if [ -f /system/priv-app/com.themoon.y1.apk ] || [ -f /system/app/com.themoon.y1.apk ]; then\n")
                for ((staged, name) in newLibs) {
                    val tmp = "/system/lib/$name.classy_tmp"
                    os.writeBytes("  cp ${staged.absolutePath} $tmp >> $logPath 2>&1 && chmod 644 $tmp && chown root:root $tmp || ok=0\n")
                }
                os.writeBytes("  if [ \"\$ok\" = 1 ]; then\n")
                for ((_, name) in newLibs) {
                    // mv, not an overwrite in place: the running app has the old library mapped
                    os.writeBytes("    mv /system/lib/$name.classy_tmp /system/lib/$name >> $logPath 2>&1 || ok=0\n")
                }
                os.writeBytes("  fi\n")
                for ((_, name) in newLibs) os.writeBytes("  rm -f /system/lib/$name.classy_tmp\n")
                os.writeBytes("fi\n")
                for ((staged, _) in newLibs) os.writeBytes("rm -f ${staged.absolutePath}\n")
                os.writeBytes("if [ \"\$ok\" != 1 ]; then\n")
                os.writeBytes("  echo \"native libraries could not be installed: the update stops\" >> $logPath\n")
                os.writeBytes("  mount -o remount,ro /system 2>/dev/null\n")
                os.writeBytes("  exit $LIBS_FAILED_EXIT\n")
                os.writeBytes("fi\n")
            }
            // The ROM puts the app in /system/priv-app (Y2, Android 4.4) or /system/app (Y1, Android 4.2)
            os.writeBytes("for sys_apk in /system/priv-app/com.themoon.y1.apk /system/app/com.themoon.y1.apk; do\n")
            os.writeBytes("  if [ -f \$sys_apk ]; then\n")
            os.writeBytes("    cp $apkPath \$sys_apk >> $logPath 2>&1\n")
            os.writeBytes("    chmod 644 \$sys_apk\n")
            os.writeBytes("    rm -f /data/dalvik-cache/*com.themoon.y1* 2>/dev/null\n")
            // A copy in /data/app stays: it has the same key (updateVerdict), so pm install replaces it below
            os.writeBytes("  fi\n")
            os.writeBytes("done\n")
            if (tempJarPath != null) {
                val targetJar = com.themoon.y1.migrations.BootScreenMigrator.TARGET_POLICY_JAR
                val targetOdex = com.themoon.y1.migrations.BootScreenMigrator.TARGET_POLICY_ODEX
                val stagedJar = "$targetJar.classy_tmp"
                os.writeBytes("if cp $tempJarPath $stagedJar >> $logPath 2>&1 && chmod 644 $stagedJar && chown root:root $stagedJar; then\n")
                // Plain mv: the Y2's toolbox mv has no -f (it fails with "failed on '-f'") and replaces the target anyway
                os.writeBytes("  mv $stagedJar $targetJar >> $logPath 2>&1 && rm -f $targetOdex || rm -f $stagedJar\n")
                os.writeBytes("else\n")
                os.writeBytes("  rm -f $stagedJar\n")
                os.writeBytes("fi\n")
                os.writeBytes("rm -f $tempJarPath\n")
            }
            if (tempApkPath != null) {
                val targetApk = com.themoon.y1.migrations.BootScreenMigrator.TARGET_FRAMEWORK_RES
                val stagedApk = "$targetApk.classy_tmp"
                os.writeBytes("if cp $tempApkPath $stagedApk >> $logPath 2>&1 && chmod 644 $stagedApk && chown root:root $stagedApk; then\n")
                os.writeBytes("  mv $stagedApk $targetApk >> $logPath 2>&1 || rm -f $stagedApk\n")
                os.writeBytes("else\n")
                os.writeBytes("  rm -f $stagedApk\n")
                os.writeBytes("fi\n")
                os.writeBytes("rm -f $tempApkPath\n")
            }
            os.writeBytes("pm install -r -d $apkPath >> $logPath 2>&1\n")
            os.writeBytes("sync\n")
            os.writeBytes("reboot\n")
            os.writeBytes("sleep 3\n")
            os.writeBytes("am start -n " + activity.packageName + "/.MainActivity\n")
            os.writeBytes("exit\n")
            os.flush()
            os.close()

            // The libraries could not be installed, and nothing else changed (on success the player restarts, and the
            // shell can end with any code)
            if (process.waitFor() == LIBS_FAILED_EXIT) {
                apkFile.delete()
                activity.runOnUiThread { Notice.error(activity, localize("Install Failed.")) }
            }
            return
        } catch (_: Exception) {}

        // Fallback for non-root systems
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri = Uri.parse("file://" + apkFile.absolutePath)
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            activity.startActivity(intent)
        } catch (_: Exception) {
            activity.runOnUiThread {
                Notice.error(activity, localize("Install Failed."))
            }
        }
    }
}
