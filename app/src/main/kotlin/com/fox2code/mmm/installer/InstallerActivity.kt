/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("NAME_SHADOWING")

package com.fox2code.mmm.installer

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.recyclerview.widget.RecyclerView
import com.fox2code.androidansi.AnsiConstants
import com.fox2code.androidansi.AnsiParser
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.app.FoxActivity.OnBackPressedCallback
import com.fox2code.mmm.AppUpdateManager
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks
import com.fox2code.mmm.androidacy.AndroidacyUtil
import com.fox2code.mmm.module.ActionButtonType
import com.fox2code.mmm.utils.FastException
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.RuntimeUtils
import com.fox2code.mmm.utils.io.Files.Companion.copy
import com.fox2code.mmm.utils.io.Files.Companion.fixJavaZipHax
import com.fox2code.mmm.utils.io.Files.Companion.fixSourceArchiveShit
import com.fox2code.mmm.utils.io.Files.Companion.patchModuleSimple
import com.fox2code.mmm.utils.io.Files.Companion.readAllBytes
import com.fox2code.mmm.utils.io.Files.Companion.readSU
import com.fox2code.mmm.utils.io.Files.Companion.write
import com.fox2code.mmm.utils.io.Hashes.Companion.checkSumMatch
import com.fox2code.mmm.utils.io.PropUtils
import com.fox2code.mmm.utils.io.net.Http
import com.fox2code.mmm.utils.sentry.SentryBreadcrumb
import com.fox2code.mmm.utils.sentry.SentryMain
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.superuser.io.SuFile
import org.apache.commons.compress.archivers.zip.ZipFile
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Enumeration
import java.util.concurrent.Executor
import java.util.zip.ZipEntry

class InstallerActivity : FoxActivity() {
    private var progressIndicator: LinearProgressIndicator? = null
    private var rebootFloatingButton: BottomNavigationItemView? = null
    private var cancelFloatingButton: BottomNavigationItemView? = null
    private var installerTerminal: InstallerTerminal? = null
    private var moduleCache: File? = null
    private var toDelete: File? = null
    private var textWrap = false
    private var canceled = false
    private var warnReboot = false
    private var wakeLock: WakeLock? = null

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        warnReboot = false
        moduleCache = File(this.cacheDir, "installer")
        if (!moduleCache!!.exists() && !moduleCache!!.mkdirs()) Timber.e("Failed to mkdir module cache dir!")
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(MainApplication.INSTANCE!!.tracker)
        setDisplayHomeAsUpEnabled(true)
        setActionBarBackground(null)
        setOnBackPressedCallback { _: FoxActivity? ->
            canceled = true
            false
        }
        val intent = this.intent
        val target: String
        val name: String?
        val checksum: String?
        val noExtensions: Boolean
        val rootless: Boolean
        val mmtReborn: Boolean
        // Should we allow 3rd part app to install modules?
        if (Constants.INTENT_INSTALL_INTERNAL == intent.action) {
            if (!MainApplication.checkSecret(intent)) {
                Timber.e("Security check failed!")
                forceBackPressed()
                return
            }
            // ensure the intent is from our app, and is either a url or within our directory. replace all instances of .. and url encoded .. and remove whitespace
            target = intent.getStringExtra(Constants.EXTRA_INSTALL_PATH)!!.replace(
                Regex("(\\.\\.|%2E%2E|%252E%252E|%20)"), ""
            )
            if (target.isEmpty() || !target.startsWith(MainApplication.INSTANCE!!.dataDir.absolutePath) && !target.startsWith(
                    "https://"
                )
            ) {
                forceBackPressed()
                return
            }
            name = intent.getStringExtra(Constants.EXTRA_INSTALL_NAME)
            checksum = intent.getStringExtra(Constants.EXTRA_INSTALL_CHECKSUM)
            noExtensions = intent.getBooleanExtra( // Allow intent to disable extensions
                Constants.EXTRA_INSTALL_NO_EXTENSIONS, false
            )
            rootless = intent.getBooleanExtra( // For debug only
                Constants.EXTRA_INSTALL_TEST_ROOTLESS, false
            )
            mmtReborn = intent.getBooleanExtra( // For debug only
                Constants.EXTRA_INSTALL_MMT_REBORN, false
            )
        } else {
            Toast.makeText(this, "Unknown intent!", Toast.LENGTH_SHORT).show()
            forceBackPressed()
            return
        }
        // Note: Sentry only send this info on crash.
        if (MainApplication.isCrashReportingEnabled) {
            val breadcrumb = SentryBreadcrumb()
            breadcrumb.setType("install")
            breadcrumb.setData("target", target)
            breadcrumb.setData("name", name)
            breadcrumb.setData("checksum", checksum)
            breadcrumb.setCategory("app.action.preinstall")
            SentryMain.addSentryBreadcrumb(breadcrumb)
        }
        val urlMode = target.startsWith("http://") || target.startsWith("https://")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = name
        textWrap = MainApplication.isTextWrapEnabled
        setContentView(if (textWrap) R.layout.installer_wrap else R.layout.installer)
        val background: Int
        val foreground: Int
        if (MainApplication.INSTANCE!!.isLightTheme && !MainApplication.isForceDarkTerminal) {
            background = Color.WHITE
            foreground = Color.BLACK
        } else {
            background = Color.BLACK
            foreground = Color.WHITE
        }
        val horizontalScroller = findViewById<View>(R.id.install_horizontal_scroller)
        var installTerminal: RecyclerView
        progressIndicator = findViewById(R.id.progress_bar)
        rebootFloatingButton = findViewById(R.id.install_terminal_reboot_fab)
        cancelFloatingButton = findViewById(R.id.back_installer)
        val rbtBtn = rebootFloatingButton
        val cnlBtn = cancelFloatingButton
        // disable both
        rbtBtn?.isEnabled = false
        cnlBtn?.isEnabled = false
        installerTerminal =
            InstallerTerminal(findViewById<RecyclerView>(R.id.install_terminal).also {
                installTerminal = it
            }, this.isLightTheme, foreground, mmtReborn)
        (horizontalScroller ?: installTerminal).background = ColorDrawable(background)
        installTerminal.itemAnimator = null
        val prgInd = progressIndicator
        prgInd?.visibility = View.GONE
        prgInd?.isIndeterminate = true
        this.window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        // acquire wakelock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fox:Installer")
        prgInd?.visibility = View.VISIBLE
        if (urlMode) installerTerminal!!.addLine("- Downloading $name")
        TrackHelper.track().event("installer_start", name)
            .with(MainApplication.INSTANCE!!.tracker)
        Thread(Runnable {

            // ensure module cache is is in our cache dir
            if (urlMode && !moduleCache!!.absolutePath.startsWith(MainApplication.INSTANCE!!.cacheDir.absolutePath)) throw SecurityException(
                "Module cache is not in cache dir!"
            )
            toDelete = if (urlMode) File(moduleCache, "module.zip") else File(
                target
            )
            val moduleCache = toDelete
            if (urlMode && moduleCache!!.exists() && !moduleCache.delete() && !SuFile(moduleCache.absolutePath).delete()) Timber.e(
                "Failed to delete module cache"
            )
            var errMessage = "Failed to download module zip"
            // Set this to the error message if it's a HTTP error
            var rawModule: ByteArray? = ByteArray(0)
            try {
                Timber.i(
                    "%s%s", if (urlMode) "Downloading: " else "Loading: ", AndroidacyUtil.hideToken(
                        target
                    )
                )
                if (urlMode) {
                    rawModule = Http.doHttpGet(
                        target
                    ) ProgressListener@{ progress: Int, max: Int, _: Boolean ->
                        if (max <= 0 && prgInd!!.isIndeterminate) return@ProgressListener
                        runOnUiThread {
                            prgInd?.isIndeterminate = false
                            prgInd?.max = max
                            prgInd?.setProgressCompat(progress, true)
                        }
                    }
                } else rawModule = readSU(moduleCache!!)
                runOnUiThread {
                    prgInd?.visibility = View.GONE
                    prgInd?.isIndeterminate = true
                }
                if (canceled) return@Runnable
                if (!checksum.isNullOrEmpty()) {
                    Timber.i("Checking for checksum: %s", checksum.toString())
                    runOnUiThread { installerTerminal!!.addLine("- Checking file integrity") }
                    if (!checkSumMatch(rawModule, checksum)) {
                        setInstallStateFinished(false, "! File integrity check failed", "")
                        return@Runnable
                    }
                }
                if (canceled) return@Runnable
                fixJavaZipHax(rawModule!!)
                // checks to make sure zip is not a source archive, and if it is, unzips the folder within, switches to it, and zips up the contents of it
                fixSourceArchiveShit(rawModule)
                var noPatch = false
                var isModule = false
                var isAnyKernel3 = false
                var isInstallZipModule = false
                errMessage = "File is not a valid zip file"
                // use apache commons to unzip the zip file, with a try-with-resources to ensure it's closed
                // write the zip file to a temporary file
                val zipFileTemp = File(this.cacheDir, "module.zip")
                FileOutputStream(zipFileTemp).use { fos -> fos.write(rawModule) }
                try {
                    ZipFile(zipFileTemp).use { zipFile ->
                        // get the zip entries
                        val zipEntries: Enumeration<out ZipEntry> = zipFile.entries
                        // iterate over the zip entries
                        while (zipEntries.hasMoreElements()) {
                            // get the next zip entry
                            val zipEntry = zipEntries.nextElement()
                            // get the name of the zip entry
                            val entryName = zipEntry.name
                            // check if the zip entry is a directory
                            if (entryName == "tools/ak3-core.sh") {
                                noPatch = true
                                isAnyKernel3 = true
                                break
                            } else if (entryName == "module.prop") {
                                noPatch = true
                                isModule = true
                                break
                            }
                            if (entryName == "META-INF/com/google/android/magisk/module.prop") {
                                noPatch = true
                                isInstallZipModule = true
                                break
                            } else if (entryName.endsWith("/tools/ak3-core.sh")) {
                                isAnyKernel3 = true
                            } else if (entryName.endsWith("/META-INF/com/google/android/update-binary")) {
                                isInstallZipModule = true
                            } else if (entryName.endsWith("/module.prop")) {
                                isModule = true
                            }
                        }
                    }
                } catch (e: IOException) {
                    Timber.e(e, "Failed to read zip file")
                    setInstallStateFinished(false, errMessage, "")
                    return@Runnable
                }
                if (!isModule && !isAnyKernel3 && !isInstallZipModule) {
                    setInstallStateFinished(
                        false,
                        "! File is not a valid Magisk module or AnyKernel3 zip",
                        ""
                    )
                    return@Runnable
                }
                if (noPatch) {
                    if (urlMode) {
                        errMessage = "Failed to save module zip"
                        FileOutputStream(moduleCache).use { outputStream ->
                            outputStream.write(rawModule)
                            outputStream.flush()
                        }
                    }
                } else {
                    errMessage = "Failed to patch module zip"
                    runOnUiThread { installerTerminal!!.addLine("- Patching $name") }
                    FileOutputStream(moduleCache).use { outputStream ->
                        patchModuleSimple(rawModule!!, outputStream)
                        outputStream.flush()
                    }
                }
                rawModule = null // Because reference is kept when calling doInstall
                if (canceled) return@Runnable
                runOnUiThread { installerTerminal!!.addLine("- Installing $name") }
                errMessage = "Failed to install module zip"
                doInstall(moduleCache, noExtensions, rootless)
            } catch (e: IOException) {
                Timber.e(e)
                setInstallStateFinished(false, errMessage, null)
            } catch (e: OutOfMemoryError) {
                rawModule = null // Because reference is kept when calling setInstallStateFinished
                if ("Failed to install module zip" == errMessage) throw e // Ignore if in installation state.
                Timber.e(e)
                setInstallStateFinished(
                    false,
                    "! Module is too large to be loaded on this device",
                    ""
                )
            }
        }, "Module install Thread").start()
    }

    @Suppress("KotlinConstantConditions")
    @Keep
    private fun doInstall(file: File?, noExtensions: Boolean, rootless: Boolean) {
        @Suppress("NAME_SHADOWING") var noExtensions = noExtensions
        if (canceled) return
        UiThreadHandler.runAndWait {
            this.onBackPressedCallback = DISABLE_BACK_BUTTON
            setDisplayHomeAsUpEnabled(false)
        }
        Timber.i("Installing: %s", moduleCache!!.name)
        val installerController = InstallerController(
            progressIndicator,
            installerTerminal,
            file!!.absoluteFile,
            noExtensions
        )
        val installerMonitor: InstallerMonitor
        val installJob: Shell.Job
        if (rootless) { // rootless is only used for debugging
            val installScript = extractInstallScript("module_installer_test.sh")
            if (installScript == null) {
                setInstallStateFinished(false, "! Failed to extract test install script", "")
                return
            }
            installerTerminal!!.enableAnsi()
            try {
                ZipFile(file).use { zipFile ->
                    val zipEntry = zipFile.getEntry("customize.sh")
                    if (zipEntry != null) {
                        FileOutputStream(
                            File(
                                file.parentFile,
                                "customize.sh"
                            )
                        ).use { fileOutputStream ->
                            copy(
                                zipFile.getInputStream(zipEntry),
                                fileOutputStream
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.i(e)
            }
            installerMonitor = InstallerMonitor(installScript)
            installJob = Shell.cmd(
                "export MMM_EXT_SUPPORT=1",
                "export MMM_USER_LANGUAGE=" + this.resources.configuration.locales[0].toLanguageTag(),
                "export MMM_APP_VERSION=" + BuildConfig.VERSION_NAME,
                "export MMM_TEXT_WRAP=" + if (textWrap) "1" else "0",
                AnsiConstants.ANSI_CMD_SUPPORT,
                "cd \"" + moduleCache!!.absolutePath + "\"",
                "sh \"" + installScript.absolutePath + "\"" + " 3 0 \"" + file.absolutePath + "\""
            ).to(installerController, installerMonitor)
        } else {
            var arch32 = "true" // Do nothing by default
            var needs32bit = false
            var moduleId: String? = null
            var anyKernel3 = false
            var magiskModule = false
            var installZipMagiskModule = false
            var mmtReborn = false
            val mgskPath = InstallerInitializer.peekMagiskPath()
            if (mgskPath == null) {
                setInstallStateFinished(false, "! Unable to resolve magisk path", "")
                return
            }
            val ashExec = "$mgskPath/.magisk/busybox/busybox ash"
            try {
                ZipFile(file).use { zipFile ->
                    // Check if module is AnyKernel module
                    if (zipFile.getEntry("tools/ak3-core.sh") != null) {
                        val updateBinary =
                            zipFile.getEntry("META-INF/com/google/android/update-binary")
                        if (updateBinary != null) {
                            val bufferedReader =
                                BufferedReader(InputStreamReader(zipFile.getInputStream(updateBinary)))
                            var line: String
                            val iterator = bufferedReader.lineSequence().iterator()
                            // same as above, but with the iterator
                            while (iterator.hasNext()) {
                                line = iterator.next()
                                if (line.contains("AnyKernel3")) {
                                    anyKernel3 = true
                                    break
                                }
                            }
                            bufferedReader.close()
                        }
                    }
                    if ((zipFile.getEntry( // Check if module hard require 32bit support
                            "common/addon/Volume-Key-Selector/tools/arm64/keycheck"
                        ) == null && zipFile.getEntry("common/addon/Volume-Key-Selector/install.sh") != null || zipFile.getEntry(
                            "META-INF/zbin/keycheck_arm64"
                        ) == null) && zipFile.getEntry("META-INF/zbin/keycheck_arm") != null
                    ) {
                        needs32bit = true
                    }
                    var moduleProp = zipFile.getEntry("module.prop")
                    magiskModule = moduleProp != null
                    if (zipFile.getEntry("install.sh") == null && zipFile.getEntry("customize.sh") == null && zipFile.getEntry(
                            "setup.sh"
                        ) != null && magiskModule
                    ) {
                        mmtReborn = true // MMT-Reborn require a separate runtime
                    }
                    if (!magiskModule && zipFile.getEntry("META-INF/com/google/android/magisk/module.prop")
                            .also { moduleProp = it } != null
                    ) {
                        installZipMagiskModule = true
                    }
                    moduleId = PropUtils.readModuleId(zipFile.getInputStream(moduleProp))
                }
            } catch (ignored: IOException) {
            }
            val compatFlags = AppUpdateManager.getFlagsForModule(moduleId!!)
            if (compatFlags and AppUpdateManager.FLAG_COMPAT_NEED_32BIT != 0) needs32bit = true
            if (compatFlags and AppUpdateManager.FLAG_COMPAT_NO_EXT != 0) noExtensions = true
            if (moduleId != null && (moduleId!!.isEmpty() || moduleId!!.contains("/") || moduleId!!.contains(
                    "\u0000"
                ) || moduleId!!.startsWith(".") && moduleId!!.endsWith("."))
            ) {
                setInstallStateFinished(false, "! This module contain a dangerous moduleId", null)
                return
            }
            if (magiskModule && moduleId == null && !anyKernel3) {
                // Modules without module Ids are module installed by 3rd party software
                setInstallStateFinished(false, "! Magisk modules require a moduleId", null)
                return
            }
            if (anyKernel3) {
                installerController.useRecoveryExt()
            } else if (needs32bit || compatFlags and AppUpdateManager.FLAG_COMPAT_NO_EXT == 0) {
                // Restore Magisk legacy stuff for retro compatibility
                if (Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()) {
                    if (Build.SUPPORTED_32_BIT_ABIS[0].contains("arm")) arch32 = "export ARCH32=arm"
                    if (Build.SUPPORTED_32_BIT_ABIS[0].contains("x86")) arch32 = "export ARCH32=x86"
                }
            }
            val installCommand: String
            val installExecutable: File?
            var magiskCmdLine = false
            if (anyKernel3 && moduleId == null) { // AnyKernel zip don't have a moduleId
                warnReboot = true // We should probably re-flash magisk...
                installExecutable = extractInstallScript("anykernel3_installer.sh")
                if (installExecutable == null) {
                    setInstallStateFinished(
                        false,
                        "! Failed to extract AnyKernel3 install script",
                        ""
                    )
                    return
                }
                // "unshare -m" is needed to force mount namespace isolation.
                // This allow AnyKernel to mess-up with mounts point without crashing the system!
                installCommand =
                    "unshare -m " + ashExec + " \"" + installExecutable.absolutePath + "\"" + " 3 1 \"" + file.absolutePath + "\""
            } else if (installZipMagiskModule || compatFlags and AppUpdateManager.FLAG_COMPAT_ZIP_WRAPPER != 0) {
                installExecutable = extractInstallScript("module_installer_wrapper.sh")
                if (installExecutable == null) {
                    setInstallStateFinished(
                        false,
                        "! Failed to extract Magisk module wrapper script",
                        ""
                    )
                    return
                }
                installCommand =
                    ashExec + " \"" + installExecutable.absolutePath + "\"" + " 3 1 \"" + file.absolutePath + "\""
            } else if (InstallerInitializer.peekMagiskVersion() >= Constants.MAGISK_VER_CODE_INSTALL_COMMAND && (compatFlags and AppUpdateManager.FLAG_COMPAT_MAGISK_CMD != 0 || noExtensions || MainApplication.isUsingMagiskCommand)) {
                installCommand = "magisk --install-module \"" + file.absolutePath + "\""
                installExecutable =
                    File(if (mgskPath == "/sbin") "/sbin/magisk" else "/system/bin/magisk")
                magiskCmdLine = true
            } else if (moduleId != null) {
                installExecutable = extractInstallScript("module_installer_compat.sh")
                if (installExecutable == null) {
                    setInstallStateFinished(
                        false,
                        "! Failed to extract Magisk module install script",
                        ""
                    )
                    return
                }
                installCommand =
                    ashExec + " \"" + installExecutable.absolutePath + "\"" + " 3 1 \"" + file.absolutePath + "\""
            } else {
                setInstallStateFinished(
                    false,
                    "! Zip file is not a valid Magisk module or AnyKernel3 zip!",
                    ""
                )
                return
            }
            installerMonitor = InstallerMonitor(installExecutable)
            if (moduleId != null) installerMonitor.setForCleanUp(moduleId)
            installJob = if (noExtensions) {
                if (compatFlags and AppUpdateManager.FLAG_COMPAT_FORCE_ANSI != 0) installerTerminal!!.enableAnsi() else installerTerminal!!.disableAnsi()
                Shell.cmd(
                    arch32,
                    "export BOOTMODE=true",  // No Extensions
                    if (installerTerminal!!.isAnsiEnabled) AnsiConstants.ANSI_CMD_SUPPORT else "true",
                    "cd \"" + moduleCache!!.absolutePath + "\"",
                    installCommand
                ).to(installerController, installerMonitor)
            } else {
                if (compatFlags and AppUpdateManager.FLAG_COMPAT_NO_ANSI != 0) installerTerminal!!.disableAnsi() else installerTerminal!!.enableAnsi()
                Shell.cmd(
                    arch32,
                    "export MMM_EXT_SUPPORT=1",
                    "export MMM_USER_LANGUAGE=" + this.resources.configuration.locales[0].toLanguageTag(),
                    "export MMM_APP_VERSION=" + BuildConfig.VERSION_NAME,
                    "export MMM_TEXT_WRAP=" + if (textWrap) "1" else "0",
                    if (installerTerminal!!.isAnsiEnabled) AnsiConstants.ANSI_CMD_SUPPORT else "true",
                    if (mmtReborn) "export MMM_MMT_REBORN=1" else "true",
                    "export BOOTMODE=true",
                    if (anyKernel3) "export AK3TMPFS=" + InstallerInitializer.peekMagiskPath() + "/ak3tmpfs" else "cd \"" + moduleCache!!.absolutePath + "\"",
                    installCommand
                ).to(installerController, installerMonitor)
            }
            // Note: Sentry only send this info on crash.
            if (MainApplication.isCrashReportingEnabled) {
                val breadcrumb = SentryBreadcrumb()
                breadcrumb.setType("install")
                breadcrumb.setData("moduleId", if (moduleId == null) "<null>" else moduleId)
                breadcrumb.setData("mmtReborn", if (mmtReborn) "true" else "false")
                breadcrumb.setData("isAnyKernel3", if (anyKernel3) "true" else "false")
                breadcrumb.setData("noExtensions", if (noExtensions) "true" else "false")
                breadcrumb.setData("magiskCmdLine", if (magiskCmdLine) "true" else "false")
                breadcrumb.setData(
                    "ansi",
                    if (installerTerminal!!.isAnsiEnabled) "enabled" else "disabled"
                )
                breadcrumb.setCategory("app.action.install")
                SentryMain.addSentryBreadcrumb(breadcrumb)
            }
            if (mmtReborn && magiskCmdLine) {
                Timber.w("mmtReborn and magiskCmdLine may not work well together")
            }
        }
        var success = installJob.exec().isSuccess
        // Wait one UI cycle before disabling controller or processing results
        UiThreadHandler.runAndWait {} // to avoid race conditions
        installerController.disable()
        var message = "- Install successful"
        if (!success) {
            // Workaround busybox-ndk install recognized as failed when successful
            if (installerTerminal!!.lastLine.trim { it <= ' ' } == "Done!") {
                success = true
            } else {
                message = installerMonitor.doCleanUp()
            }
        }
        setInstallStateFinished(success, message, installerController.supportLink)
    }

    private fun extractInstallScript(script: String): File? {
        val compatInstallScript = File(moduleCache, script)
        if (!compatInstallScript.exists() || compatInstallScript.length() == 0L || !extracted.contains(
                script
            )
        ) {
            try {
                write(compatInstallScript, readAllBytes(this.assets.open(script)))
                extracted.add(script)
            } catch (e: IOException) {
                if (compatInstallScript.delete()) extracted.remove(script)
                Timber.e(e)
                return null
            }
        }
        return compatInstallScript
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.dispatchKeyEvent(
            event
        )
    }

    @SuppressLint("RestrictedApi")
    private fun setInstallStateFinished(success: Boolean, message: String?, optionalLink: String?) {
        installerTerminal!!.disableAnsi()
        if (success && toDelete != null && !toDelete!!.delete()) {
            val suFile = SuFile(toDelete!!.absolutePath)
            if (suFile.exists() && !suFile.delete()) Timber.w("Failed to delete zip file") else toDelete =
                null
        } else toDelete = null
        runOnUiThread {
            this.window.setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, 0)
            // release wakelock
            if (wakeLock != null && wakeLock!!.isHeld) {
                wakeLock!!.release()
                wakeLock = null
            }
            // Set the back press to finish the activity and return to the main activity
            this.onBackPressedCallback = OnBackPressedCallback { _: FoxActivity? ->
                finishAndRemoveTask()
                startActivity(Intent(this, MainActivity::class.java))
                true
            }
            setDisplayHomeAsUpEnabled(true)
            progressIndicator!!.visibility = View.GONE
            rebootFloatingButton!!.setOnClickListener { _: View? ->
                if (MainApplication.shouldPreventReboot()) {
                    // toast and do nothing
                    Toast.makeText(
                        this,
                        R.string.install_terminal_reboot_prevented,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val builder = MaterialAlertDialogBuilder(this)
                    builder.setTitle(R.string.install_terminal_reboot_now)
                        .setMessage(R.string.install_terminal_reboot_now_message)
                        .setCancelable(false).setIcon(
                            R.drawable.ic_reboot_24
                        ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                            RuntimeUtils.reboot(this, RuntimeUtils.RebootMode.REBOOT)
                        }
                        .setNegativeButton(R.string.no) { x: DialogInterface, _: Int -> x.dismiss() }
                        .show()
                }
            }
            rebootFloatingButton!!.isEnabled = true
            cancelFloatingButton!!.isEnabled = true
            // handle back button
            cancelFloatingButton!!.setOnClickListener { _: View? -> forceBackPressed() }
            if (!message.isNullOrEmpty()) installerTerminal!!.addLine(message)
            if (!optionalLink.isNullOrEmpty()) {
                this.setActionBarExtraMenuButton(ActionButtonType.supportIconForUrl(optionalLink)) { _: MenuItem? ->
                    IntentHelper.openUrl(this, optionalLink)
                    true
                }
            } else if (success) {
                val intent = this.intent
                val config =
                    if (MainApplication.checkSecret(intent)) intent.getStringExtra(Constants.EXTRA_INSTALL_CONFIG) else null
                if (!config.isNullOrEmpty()) {
                    val configPkg = IntentHelper.getPackageOfConfig(config)
                    try {
                        XHooks.checkConfigTargetExists(this, configPkg, config)
                        this.setActionBarExtraMenuButton(R.drawable.ic_baseline_app_settings_alt_24) { _: MenuItem? ->
                            IntentHelper.openConfig(this, config)
                            true
                        }
                    } catch (e: PackageManager.NameNotFoundException) {
                        Timber.w("Config package \"$configPkg\" missing for installer view")
                        installerTerminal!!.addLine(
                            String.format(
                                this.getString(R.string.install_terminal_config_missing),
                                configPkg
                            )
                        )
                    }
                }
            }
        }
    }

    class InstallerController(
        private val progressIndicator: LinearProgressIndicator?,
        private val terminal: InstallerTerminal?,
        private val moduleFile: File,
        private val noExtension: Boolean
    ) : CallbackList<String?>() {
        private var enabled = true
        private var useExt = false
        private var useRecovery = false
        private var isRecoveryBar = false
        var supportLink = ""
            private set

        override fun onAddElement(s: String?) {
            var s = s ?: return
            if (!enabled) return
            Timber.i("MSG: %s", s)
            if ("#!useExt" == s.trim { it <= ' ' } && !noExtension) {
                useExt = true
                return
            }
            s = AnsiParser.patchEscapeSequences(s)
            if (useExt && s.startsWith("#!")) {
                processCommand(s.substring(2))
            } else if (useRecovery && s.startsWith("progress ")) {
                val tokens = s.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                try {
                    var progress = tokens[1].toFloat()
                    val max = tokens[2].toFloat()
                    val progressInt: Int
                    if (max <= 0f) {
                        return
                    } else if (progress >= max) {
                        progressInt = 256
                    } else {
                        if (progress <= 0f) progress = 0f
                        progressInt = (256.0 * progress / max).toInt()
                    }
                    processCommand("showLoading 256")
                    processCommand("setLoading $progressInt")
                    isRecoveryBar = true
                } catch (ignored: Exception) {
                }
            } else {
                terminal!!.addLine(s.replace(moduleFile.absolutePath, moduleFile.name))
            }
        }

        private fun processCommand(rawCommand: String) {
            val arg: String
            val command: String
            val i = rawCommand.indexOf(' ')
            if (i != -1 && rawCommand.length != i + 1) {
                arg = rawCommand.substring(i + 1).trim { it <= ' ' }
                command = rawCommand.substring(0, i)
            } else {
                arg = ""
                command = rawCommand
            }
            when (command) {
                "useRecovery" -> useRecovery = true
                "addLine" -> terminal!!.addLine(arg)
                "setLastLine" -> terminal!!.lastLine = arg
                "clearTerminal" -> terminal!!.clearTerminal()
                "scrollUp" -> terminal!!.scrollUp()
                "scrollDown" -> terminal!!.scrollDown()
                "showLoading" -> {
                    isRecoveryBar = false
                    if (arg.isNotEmpty()) {
                        try {
                            val s = arg.toShort()
                            if (s <= 0) throw FastException.INSTANCE
                            progressIndicator!!.max = s.toInt()
                            progressIndicator.isIndeterminate = false
                        } catch (ignored: Exception) {
                            progressIndicator!!.setProgressCompat(0, true)
                            progressIndicator.max = 100
                            if (progressIndicator.visibility == View.VISIBLE) {
                                progressIndicator.visibility = View.GONE
                            }
                            progressIndicator.isIndeterminate = true
                        }
                    } else {
                        progressIndicator!!.setProgressCompat(0, true)
                        progressIndicator.max = 100
                        if (progressIndicator.visibility == View.VISIBLE) {
                            progressIndicator.visibility = View.GONE
                        }
                        progressIndicator.isIndeterminate = true
                    }
                    progressIndicator!!.visibility = View.VISIBLE
                }

                "setLoading" -> {
                    isRecoveryBar = false
                    try {
                        progressIndicator!!.setProgressCompat(arg.toShort().toInt(), true)
                    } catch (ignored: Exception) {
                    }
                }

                "hideLoading" -> {
                    isRecoveryBar = false
                    progressIndicator!!.visibility = View.GONE
                }

                "setSupportLink" -> {
                    // Only set link if valid
                    if (arg.isEmpty() || arg.startsWith("https://") && arg.indexOf(
                            '/',
                            8
                        ) > 8
                    ) supportLink = arg
                }

                "disableANSI" -> terminal!!.disableAnsi()
            }
        }

        fun useRecoveryExt() {
            useRecovery = true
        }

        fun disable() {
            enabled = false
            if (isRecoveryBar) {
                UiThreadHandler.runAndWait { processCommand("setLoading 256") }
            }
        }
    }

    class InstallerMonitor(installScript: File) : CallbackList<String?>(
        Executor { obj: Runnable -> obj.run() }) {
        private val installScriptErr: String
        private var lastCommand = ""
        private var forCleanUp: String? = null

        init {
            installScriptErr = "${installScript.absolutePath}: /data/adb/modules_update/"
        }

        override fun onAddElement(e: String?) {
            val e = e ?: return
            Timber.i("Monitor: %s", e)
            lastCommand = e
        }

        fun setForCleanUp(forCleanUp: String?) {
            this.forCleanUp = forCleanUp
        }

        fun doCleanUp(): String {
            var installScriptErr = installScriptErr
            // This block is mainly to help fixing customize.sh syntax errors
            if (lastCommand.startsWith(installScriptErr)) {
                installScriptErr = lastCommand.substring(installScriptErr.length)
                val i = installScriptErr.indexOf('/')
                if (i == -1) return DEFAULT_ERR
                val module = installScriptErr.substring(0, i)
                val moduleUpdate = SuFile("/data/adb/modules_update/$module")
                if (moduleUpdate.exists()) {
                    if (!moduleUpdate.deleteRecursive()) Timber.e("Failed to delete failed update")
                    return "Error: " + installScriptErr.substring(i + 1)
                }
            } else if (forCleanUp != null) {
                val moduleUpdate = SuFile("/data/adb/modules_update/$forCleanUp")
                if (moduleUpdate.exists() && !moduleUpdate.deleteRecursive()) Timber.e("Failed to delete failed update")
            }
            return DEFAULT_ERR
        }

        companion object {
            private const val DEFAULT_ERR = "! Install failed"
        }
    }

    companion object {
        private val extracted = HashSet<String>()
    }
}
