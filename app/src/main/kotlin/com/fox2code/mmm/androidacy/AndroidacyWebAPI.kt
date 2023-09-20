/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("NAME_SHADOWING", "MemberVisibilityCanBePrivate")

package com.fox2code.mmm.androidacy

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.getModuleId
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.getModuleTitle
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.hideToken
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyFileUrl
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyLink
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.IntentHelper.Companion.openCustomTab
import com.fox2code.mmm.utils.IntentHelper.Companion.openInstaller
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrl
import com.fox2code.mmm.utils.IntentHelper.Companion.startDownloadUsingDownloadManager
import com.fox2code.mmm.utils.io.Files.Companion.readSU
import com.fox2code.mmm.utils.io.Files.Companion.writeSU
import com.fox2code.mmm.utils.io.Hashes.Companion.checkSumFormat
import com.fox2code.mmm.utils.io.Hashes.Companion.checkSumName
import com.fox2code.mmm.utils.io.Hashes.Companion.checkSumValid
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Objects

@Suppress("SameReturnValue")
@Keep
class AndroidacyWebAPI(
    private val activity: AndroidacyActivity,
    private val allowInstall: Boolean
) {
    var consumedAction = false
    var downloadMode = false

    /**
     * Allow Androidacy backend to notify compat mode
     * return current effective compat mode
     */
    @get:JavascriptInterface
    var effectiveCompatMode = 0
    var notifiedCompatMode = 0
    fun forceQuitRaw(error: String?) {
        Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
        activity.runOnUiThread { activity.finish() }
        activity.backOnResume = true // Set backOnResume just in case
        downloadMode = false
    }

    fun openNativeModuleDialogRaw(
        moduleUrl: String?,
        moduleId: String?,
        installTitle: String?,
        checksum: String?,
        canInstall: Boolean
    ) {
        if (MainApplication.forceDebugLogging) Timber.d(
            "ModuleDialog, downloadUrl: " + hideToken(
                moduleUrl!!
            ) + ", moduleId: " + moduleId + ", installTitle: " + installTitle + ", checksum: " + checksum + ", canInstall: " + canInstall
        )
        // moduleUrl should be a valid URL, i.e. in the androidacy.com domain
        // if it is not, do not proceed
        if (!isAndroidacyFileUrl(moduleUrl)) {
            Timber.e("ModuleDialog, invalid URL: %s", moduleUrl)
            return
        }
        downloadMode = false
        val repoModule = AndroidacyRepoData.instance.moduleHashMap[installTitle]
        val title: String?
        var description: String?
        var mmtReborn = false
        if (repoModule != null) {
            title = repoModule.moduleInfo.name
            description = repoModule.moduleInfo.description
            mmtReborn = repoModule.moduleInfo.mmtReborn
            if (description.isNullOrEmpty()) {
                description = activity.getString(R.string.no_desc_found)
            }
        } else {
            // URL Decode installTitle
            title = installTitle
            val checkSumType = checkSumName(checksum)
            description = if (checkSumType == null) {
                "Checksum: ${if (checksum.isNullOrEmpty()) "null" else checksum}"
            } else {
                "$checkSumType: $checksum"
            }
        }
        val builder = MaterialAlertDialogBuilder(activity)
        builder.setTitle(title).setMessage(description).setCancelable(true)
            .setIcon(R.drawable.ic_baseline_extension_24)
        builder.setNegativeButton(R.string.download_module) { _: DialogInterface?, _: Int ->
            downloadMode = true
            startDownloadUsingDownloadManager(activity, moduleUrl, title)
            // close activity
            activity.runOnUiThread { activity.finishAndRemoveTask() }
        }
        if (canInstall) {
            var hasUpdate = false
            var config: String? = null
            if (repoModule != null) {
                config = repoModule.moduleInfo.config
                val localModuleInfo = instance?.modules?.get(repoModule.id)
                hasUpdate =
                    localModuleInfo != null && repoModule.moduleInfo.versionCode > localModuleInfo.versionCode
            }
            val fConfig = config
            val fMMTReborn = mmtReborn
            builder.setPositiveButton(if (hasUpdate) R.string.update_module else R.string.install_module) { _: DialogInterface?, _: Int ->
                openInstaller(
                    activity,
                    moduleUrl,
                    title,
                    fConfig,
                    checksum,
                    fMMTReborn
                )
                // close activity
                activity.runOnUiThread { activity.finishAndRemoveTask() }
            }
        }
        builder.setOnCancelListener { _: DialogInterface? ->
            if (!activity.backOnResume) consumedAction = false
        }
        ExternalHelper.INSTANCE.injectButton(builder, {
            downloadMode = true
            try {
                return@injectButton activity.downloadFileAsync(moduleUrl)
            } catch (e: IOException) {
                Timber.e(e, "Failed to download module")
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        R.string.failed_download,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@injectButton null
            }
        }, "androidacy_repo")
        val dim5dp = activity.resources.getDimensionPixelSize(R.dimen.dim5dp)
        builder.setBackgroundInsetStart(dim5dp).setBackgroundInsetEnd(dim5dp)
        activity.runOnUiThread {
            val alertDialog = builder.show()
            for (i in -3..-1) {
                val alertButton = alertDialog.getButton(i)
                if (alertButton != null && alertButton.paddingStart > dim5dp) {
                    alertButton.setPadding(dim5dp, dim5dp, dim5dp, dim5dp)
                }
            }
        }
    }

    @Suppress("NAME_SHADOWING")
    fun notifyCompatModeRaw(value: Int) {
        var value = value
        if (consumedAction) return
        if (MainApplication.forceDebugLogging) Timber.d("Androidacy Compat mode: %s", value)
        notifiedCompatMode = value
        if (value < 0) {
            value = 0
        } else if (value > MAX_COMPAT_MODE) {
            value = MAX_COMPAT_MODE
        }
        effectiveCompatMode = value
    }

    @JavascriptInterface
    fun forceQuit(error: String?) {
        // Allow forceQuit and cancel in downloadMode
        if (consumedAction && !downloadMode) return
        consumedAction = true
        forceQuitRaw(error)
    }

    @JavascriptInterface
    fun cancel() {
        // Allow forceQuit and cancel in downloadMode
        if (consumedAction && !downloadMode) return
        consumedAction = true
        activity.runOnUiThread { activity.finish() }
    }

    /**
     * Open an url always in an external page or browser.
     */
    @JavascriptInterface
    fun openUrl(url: String?) {
        if (consumedAction) return
        consumedAction = true
        downloadMode = false
        if (MainApplication.forceDebugLogging) Timber.d("Received openUrl request: %s", url)
        if (Uri.parse(url).scheme == "https") {
            openUrl(activity, url)
        }
    }

    /**
     * Open an url in a custom tab if possible.
     */
    @JavascriptInterface
    fun openCustomTab(url: String?) {
        if (consumedAction) return
        consumedAction = true
        downloadMode = false
        if (MainApplication.forceDebugLogging) Timber.d("Received openCustomTab request: %s", url)
        if (Uri.parse(url).scheme == "https") {
            openCustomTab(activity, url)
        }
    }

    /**
     * Return if current theme is a light theme.
     */
    @get:JavascriptInterface
    val isLightTheme: Boolean
        get() = MainApplication.INSTANCE!!.isLightTheme

    /**
     * Check if the manager has received root access
     * (Note: hasRoot only return true on Magisk rooted phones)
     */
    @JavascriptInterface
    fun hasRoot(): Boolean {
        return peekMagiskPath() != null
    }

    /**
     * Check if the install API can be used
     */
    @JavascriptInterface
    fun canInstall(): Boolean {
        // With lockdown mode enabled or lack of root, install should not have any effect
        return allowInstall && hasRoot() && !MainApplication.isShowcaseMode
    }

    /**
     * install a module via url, with the file checked with the md5 checksum value.
     */
    @JavascriptInterface
    fun install(moduleUrl: String, installTitle: String?, checksum: String?) {
        // If compat mode is 0, this means Androidacy didn't implemented a download mode yet
        var installTitle = installTitle
        var checksum = checksum
        if (consumedAction || effectiveCompatMode >= 1 && !canInstall()) {
            return
        }
        consumedAction = true
        downloadMode = false
        if (MainApplication.forceDebugLogging) Timber.d("Received install request: $moduleUrl $installTitle $checksum")
        if (!isAndroidacyLink(moduleUrl)) {
            forceQuitRaw("Non Androidacy module link used on Androidacy")
            return
        }
        checksum = checkSumFormat(checksum)
        if (checksum.isNullOrEmpty()) {
            Timber.w("Androidacy didn't provided a checksum!")
        } else if (!checkSumValid(checksum)) {
            forceQuitRaw("Androidacy didn't provided a valid checksum")
            return
        }
        // moduleId is the module parameter in the url
        val moduleId = getModuleId(moduleUrl)
        // Let's handle download mode ourself if not implemented
        if (effectiveCompatMode < 1) {
            if (!canInstall()) {
                downloadMode = true
                activity.runOnUiThread {
                    if (activity.webView != null) {
                        activity.webView!!.loadUrl(moduleUrl)
                    }
                }
            } else {
                openNativeModuleDialogRaw(moduleUrl, moduleId, installTitle, checksum, true)
            }
        } else {
            val repoModule = AndroidacyRepoData.instance.moduleHashMap[installTitle]
            var config: String? = null
            var mmtReborn = false
            if (repoModule != null && Objects.requireNonNull<String?>(repoModule.moduleInfo.name).length >= 3) {
                installTitle = repoModule.moduleInfo.name // Set title to module name
                config = repoModule.moduleInfo.config
                mmtReborn = repoModule.moduleInfo.mmtReborn
            }
            activity.backOnResume = true
            openInstaller(activity, moduleUrl, installTitle, config, checksum, mmtReborn)
        }
    }

    /**
     * install a module via url, with the file checked with the md5 checksum value.
     */
    @JavascriptInterface
    fun openNativeModuleDialog(moduleUrl: String?, moduleId: String?, checksum: String?) {
        var checksum = checksum
        if (consumedAction) return
        consumedAction = true
        downloadMode = false
        if (!isAndroidacyLink(moduleUrl)) {
            forceQuitRaw("Non Androidacy module link used on Androidacy")
            return
        }
        checksum = checkSumFormat(checksum)
        if (checksum.isNullOrEmpty()) {
            Timber.w("Androidacy WebView didn't provided a checksum!")
        } else if (!checkSumValid(checksum)) {
            forceQuitRaw("Androidacy didn't provided a valid checksum")
            return
        }
        // Get moduleTitle from url
        val moduleTitle = getModuleTitle(moduleUrl!!)
        openNativeModuleDialogRaw(moduleUrl, moduleId, moduleTitle, checksum, canInstall())
    }

    /**
     * Tell if the moduleId is installed on the device
     */
    @JavascriptInterface
    fun isModuleInstalled(moduleId: String?): Boolean {
        return instance?.modules?.get(moduleId) != null
    }

    /**
     * Tell if the moduleId is updating and waiting a reboot to update
     */
    @JavascriptInterface
    fun isModuleUpdating(moduleId: String?): Boolean {
        val localModuleInfo = instance?.modules?.get(moduleId)
        return localModuleInfo != null && localModuleInfo.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING)
    }

    /**
     * Return the module version name or null if not installed.
     */
    @JavascriptInterface
    fun getModuleVersion(moduleId: String?): String? {
        val localModuleInfo = instance?.modules?.get(moduleId)
        return localModuleInfo?.version
    }

    /**
     * Return the module version code or -1 if not installed.
     */
    @JavascriptInterface
    fun getModuleVersionCode(moduleId: String?): Long {
        val localModuleInfo = instance?.modules?.get(moduleId)
        return localModuleInfo?.versionCode ?: -1L
    }

    /**
     * Hide action bar if visible, the action bar is only visible by default on notes.
     */
    @JavascriptInterface
    fun hideActionBar() {
        if (consumedAction) return
        consumedAction = true
        activity.runOnUiThread {
            consumedAction = false
        }
    }

    /**
     * Show action bar if not visible, the action bar is only visible by default on notes.
     * Optional title param to set action bar title.
     */
    @JavascriptInterface
    fun showActionBar(title: String?) {
        if (consumedAction) return
        consumedAction = true
        activity.runOnUiThread {
            if (!title.isNullOrEmpty()) {
                activity.title = title
            }
            consumedAction = false
        }
    }

    /**
     * Return true if the module is an Androidacy module.
     */
    @JavascriptInterface
    fun isAndroidacyModule(moduleId: String?): Boolean {
        val localModuleInfo = instance?.modules?.get(moduleId)
        return localModuleInfo != null && ("Androidacy" == localModuleInfo.author || isAndroidacyLink(
            localModuleInfo.config
        ))
    }

    /**
     * get a module file, return an empty string if not
     * an Androidacy module or if file doesn't exists.
     */
    @JavascriptInterface
    fun getAndroidacyModuleFile(moduleId: String, moduleFile: String?): String {
        var moduleId = moduleId
        var moduleFile = moduleFile
        moduleId = moduleId.replace("\\.".toRegex(), "").replace("/".toRegex(), "")
        if (moduleFile == null || consumedAction || !isAndroidacyModule(moduleId)) return ""
        moduleFile = moduleFile.replace("\\.".toRegex(), "").replace("/".toRegex(), "")
        val moduleFolder = File("/data/adb/modules/$moduleId")
        val absModuleFile = File(moduleFolder, moduleFile).absoluteFile
        return if (!absModuleFile.path.startsWith(moduleFolder.path)) "" else try {
            String(readSU(absModuleFile.absoluteFile), StandardCharsets.UTF_8)
        } catch (e: IOException) {
            ""
        }
    }

    /**
     * Create an ".androidacy" file with {@param content} as content
     * Return true if action succeeded
     */
    @JavascriptInterface
    fun setAndroidacyModuleMeta(moduleId: String, content: String?): Boolean {
        var moduleId = moduleId
        moduleId = moduleId.replace("\\.".toRegex(), "").replace("/".toRegex(), "")
        if (content == null || consumedAction || !isAndroidacyModule(moduleId)) return false
        val androidacyMetaFile = File("/data/adb/modules/$moduleId/.androidacy")
        return try {
            writeSU(androidacyMetaFile, content.toByteArray(StandardCharsets.UTF_8))
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Return current app version code
     */
    @get:JavascriptInterface
    val appVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    /**
     * Return current app version name
     */
    @get:JavascriptInterface
    val appVersionName: String
        get() = BuildConfig.VERSION_NAME

    /**
     * Return current magisk version code or 0 if not applicable
     */
    @get:JavascriptInterface
    val magiskVersionCode: Int
        get() = if (peekMagiskPath() == null) 0 else peekMagiskVersion()

    /**
     * Return current android sdk-int version code, see:
     * [right here](https://source.android.com/setup/start/build-numbers)
     */
    @get:JavascriptInterface
    val androidVersionCode: Int
        get() = Build.VERSION.SDK_INT

    /**
     * Return current navigation bar height or 0 if not visible
     */
    @get:JavascriptInterface
    val navigationBarHeight: Int
        get() = 48

    /**
     * Return current theme accent color
     */
    @get:JavascriptInterface
    val accentColor: Int
        get() {
            val theme = activity.theme
            val typedValue = TypedValue()
            theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
            theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
            return typedValue.data
        }

    /**
     * Return current theme background color
     */
    @get:JavascriptInterface
    val backgroundColor: Int
        get() {
            val theme = activity.theme
            val typedValue = TypedValue()
            theme.resolveAttribute(
                com.google.android.material.R.attr.backgroundColor,
                typedValue,
                true
            )
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return typedValue.data
            }
            theme.resolveAttribute(android.R.attr.background, typedValue, true)
            return typedValue.data
        }

    /**
     * Return current hex string of monet theme
     */
    @JavascriptInterface
    fun getMonetColor(id: String): String {
        @SuppressLint("DiscouragedApi") val nameResourceID = activity.resources.getIdentifier(
            "@android:color/$id", "color", activity.applicationInfo.packageName
        )
        return if (nameResourceID == 0) {
            throw IllegalArgumentException("No resource string found with name $id")
        } else {
            val color = ContextCompat.getColor(activity, nameResourceID)
            val red = Color.red(color)
            val blue = Color.blue(color)
            val green = Color.green(color)
            String.format("#%02x%02x%02x", red, green, blue)
        }
    }

    @JavascriptInterface
    fun setAndroidacyToken(token: String?) {
        AndroidacyRepoData.instance.setToken(token)
    }

    // Androidacy feature level declaration method
    @JavascriptInterface
    fun notifyCompatUnsupported() {
        notifyCompatModeRaw(COMPAT_UNSUPPORTED)
    }

    @JavascriptInterface
    fun notifyCompatDownloadButton() {
        notifyCompatModeRaw(COMPAT_DOWNLOAD)
    }

    companion object {
        const val COMPAT_UNSUPPORTED = 0
        const val COMPAT_DOWNLOAD = 1
        private const val MAX_COMPAT_MODE = 1
    }
}