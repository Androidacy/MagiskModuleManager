package com.fox2code.mmm

import com.fox2code.mmm.utils.io.Files.Companion.write
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

// See https://docs.github.com/en/rest/reference/repos#releases
@Suppress("unused")
class AppUpdateManager private constructor() {
    private val compatDataId = HashMap<String, Int>()
    private val updateLock = Any()
    private val compatFile: File = File(MainApplication.getINSTANCE().filesDir, "compat.txt")
    private var latestRelease: String?
    private var lastChecked: Long

    init {
        latestRelease = MainApplication.getBootSharedPreferences()
            .getString("updater_latest_release", BuildConfig.VERSION_NAME)
        lastChecked = 0
        if (compatFile.isFile) {
            try {
                parseCompatibilityFlags(FileInputStream(compatFile))
            } catch (ignored: IOException) {
            }
        }
    }

    // Return true if should show a notification
    fun checkUpdate(force: Boolean): Boolean {
        if (!BuildConfig.ENABLE_AUTO_UPDATER) return false
        if (!force && peekShouldUpdate()) return true
        val lastChecked = lastChecked
        if (lastChecked != 0L &&  // Avoid spam calls by putting a 60 seconds timer
            lastChecked < System.currentTimeMillis() - 60000L
        ) return force && peekShouldUpdate()
        synchronized(updateLock) {
            if (lastChecked != this.lastChecked) return peekShouldUpdate()
            try {
                val release =
                    JSONObject(String(doHttpGet(RELEASES_API_URL, false), StandardCharsets.UTF_8))
                var latestRelease: String? = null
                var preRelease = false
                // get latest_release from tag_name translated to int
                if (release.has("tag_name")) {
                    latestRelease = release.getString("tag_name")
                    preRelease = release.getBoolean("prerelease")
                }
                Timber.d("Latest release: %s, isPreRelease: %s", latestRelease, preRelease)
                if (latestRelease == null) return false
                if (preRelease) {
                    this.latestRelease = "99999999" // prevent updating to pre-release
                    return false
                }
                this.latestRelease = latestRelease
                this.lastChecked = System.currentTimeMillis()
            } catch (ioe: Exception) {
                Timber.e(ioe)
            }
        }
        return peekShouldUpdate()
    }

    fun checkUpdateCompat() {
        compatDataId.clear()
        try {
            write(compatFile, ByteArray(0))
        } catch (e: IOException) {
            Timber.e(e)
        }
        // There once lived an implementation that used a GitHub API to get the compatibility flags. It was removed because it was too slow and the API was rate limited.
        Timber.w("Remote compatibility data flags are not implemented.")
    }

    fun peekShouldUpdate(): Boolean {
        if (!BuildConfig.ENABLE_AUTO_UPDATER || BuildConfig.DEBUG) return false
        // Convert both BuildConfig.VERSION_NAME and latestRelease to int
        var currentVersion = 0
        var latestVersion = 0
        try {
            currentVersion = BuildConfig.VERSION_NAME.replace("\\D".toRegex(), "").toInt()
            latestVersion = latestRelease!!.replace("v", "").replace("\\D".toRegex(), "").toInt()
        } catch (ignored: NumberFormatException) {
        }
        return currentVersion < latestVersion
    }

    fun peekHasUpdate(): Boolean {
        return if (!BuildConfig.ENABLE_AUTO_UPDATER || BuildConfig.DEBUG) false else peekShouldUpdate()
    }

    @Suppress("UNUSED_PARAMETER")
    @Throws(IOException::class)
    private fun parseCompatibilityFlags(inputStream: InputStream) {
        compatDataId.clear()
        Timber.d("Not implemented")
    }

    fun getCompatibilityFlags(moduleId: String): Int {
        val compatFlags = compatDataId[moduleId]
        return compatFlags ?: 0
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        const val FLAG_COMPAT_LOW_QUALITY = 0x0001
        const val FLAG_COMPAT_NO_EXT = 0x0002
        const val FLAG_COMPAT_MAGISK_CMD = 0x0004
        const val FLAG_COMPAT_NEED_32BIT = 0x0008
        const val FLAG_COMPAT_MALWARE = 0x0010
        const val FLAG_COMPAT_NO_ANSI = 0x0020
        const val FLAG_COMPAT_FORCE_ANSI = 0x0040
        const val FLAG_COMPAT_FORCE_HIDE = 0x0080
        const val FLAG_COMPAT_MMT_REBORN = 0x0100
        const val FLAG_COMPAT_ZIP_WRAPPER = 0x0200
        const val RELEASES_API_URL =
            "https://api.github.com/repos/Androidacy/MagiskModuleManager/releases/latest"
        val appUpdateManager = AppUpdateManager()
        fun getFlagsForModule(moduleId: String): Int {
            return appUpdateManager.getCompatibilityFlags(moduleId)
        }

        @JvmStatic
        fun shouldForceHide(repoId: String): Boolean {
            return if (BuildConfig.DEBUG || repoId.startsWith("repo_") || repoId == "magisk_alt_repo") false else !repoId.startsWith(
                "repo_"
            ) && appUpdateManager.getCompatibilityFlags(repoId) and FLAG_COMPAT_FORCE_HIDE != 0
        }
    }
}
