/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.utils.io.Files.Companion.write
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

@Suppress("unused")
class AppUpdateManager private constructor() {
    private var changes: String? = null
    private val compatDataId = HashMap<String, Int>()
    private val updateLock = Any()
    private val compatFile: File = File(MainApplication.INSTANCE!!.filesDir, "compat.txt")
    private var latestRelease: Int?
    private var lastChecked: Long

    init {
        latestRelease = MainApplication.bootSharedPreferences
            ?.getInt("latest_vcode", BuildConfig.VERSION_CODE)
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
            if (MainApplication.forceDebugLogging) Timber.d("Checking for app updates")
            if (lastChecked != this.lastChecked) return peekShouldUpdate()
            // make a request to https://production-api.androidacy.com/amm/updates/check with appVersionCode and token/device_id/client_id
            var token = AndroidacyRepoData.token
            if (!AndroidacyRepoData.instance.isValidToken(token)) {
                Timber.w("Invalid token, not checking for updates")
                token = AndroidacyRepoData.instance.requestNewToken()
            }
            val deviceId = AndroidacyRepoData.generateDeviceId()
            val clientId = BuildConfig.ANDROIDACY_CLIENT_ID
            val url =
                "https://production-api.androidacy.com/amm/updates/check?appVersionCode=${BuildConfig.VERSION_CODE}&token=$token&device_id=$deviceId&client_id=$clientId"
            val response = doHttpGet(url, false)
            // convert response to string
            val responseString = String(response, Charsets.UTF_8)
            if (MainApplication.forceDebugLogging) Timber.d("Response: $responseString")
            // json response has a boolean shouldUpdate and an int latestVersion
            JSONObject(responseString).let {
                if (it.getBoolean("shouldUpdate")) {
                    latestRelease = it.getInt("latestVersion")
                    MainApplication.bootSharedPreferences?.edit()
                        ?.putInt("latest_vcode", latestRelease!!)?.apply()
                }
                this.changes = it.getString("changelog")
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
            currentVersion = BuildConfig.VERSION_CODE
            latestVersion = latestRelease!!
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
        if (MainApplication.forceDebugLogging) Timber.d("Not implemented")
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
        val appUpdateManager = AppUpdateManager()
        fun getFlagsForModule(moduleId: String): Int {
            return appUpdateManager.getCompatibilityFlags(moduleId)
        }

        fun shouldForceHide(repoId: String): Boolean {
            return if (BuildConfig.DEBUG || repoId.startsWith("repo_") || repoId == "magisk_alt_repo") false else !repoId.startsWith(
                "repo_"
            ) && appUpdateManager.getCompatibilityFlags(repoId) and FLAG_COMPAT_FORCE_HIDE != 0
        }
    }
}
