/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.installer

import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.utils.io.Files.Companion.existsSU
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import ly.count.android.sdk.Countly
import timber.log.Timber
import java.io.File

@Suppress("unused")
class InstallerInitializer {
    interface Callback {
        fun onPathReceived(path: String?)
        fun onFailure(error: Int)
    }

    companion object {
        var isKsu: Boolean = false
        private val MAGISK_SBIN = File("/sbin/magisk")
        private val MAGISK_SYSTEM = File("/system/bin/magisk")
        private val MAGISK_SYSTEM_EX = File("/system/xbin/magisk")
        private var mgskPth: String? = null
        private var mgskVerCode = 0
        private var hsRmdsk = false
        const val ERROR_NO_PATH = 1
        const val ERROR_NO_SU = 2
        const val ERROR_OTHER = 3
        private var tries = 0

        val errorNotification: NotificationType?
            get() {
                val hasRoot = Shell.isAppGrantedRoot()
                if (mgskPth != null && hasRoot !== java.lang.Boolean.FALSE) {
                    return null
                }
                if (hasRoot !== java.lang.Boolean.TRUE) {
                    return NotificationType.ROOT_DENIED
                }
                return NotificationType.NO_ROOT
            }

        fun peekMagiskPath(): String? {
            return mgskPth
        }

        /**
         * Note: All mirrors are read only on latest magisk
         */
        fun peekMirrorPath(): String? {
            return if (mgskPth == null) null else "$mgskPth/.magisk/mirror"
        }

        /**
         * Note: Used to detect which modules are currently loaded.
         *
         * For read/write only "/data/adb/modules" should be used
         */
        fun peekModulesPath(): String? {
            return if (mgskPth == null) null else "$mgskPth/.magisk/modules"
        }

        fun peekMagiskVersion(): Int {
            return mgskVerCode
        }

        fun peekHasRamdisk(): Boolean {
            return hsRmdsk
        }

        fun tryGetMagiskPathAsync(callback: Callback, forceCheck: Boolean = false) {
            val thread: Thread = object : Thread("Magisk GetPath Thread") {
                override fun run() {
                    if (mgskPth != null && !forceCheck) {
                        callback.onPathReceived(mgskPth)
                        return
                    }
                    var error: Int
                    try {
                        mgskPth = tryGetMagiskPath(forceCheck)
                        error = ERROR_NO_PATH
                    } catch (e: NoShellException) {
                        error = ERROR_NO_SU
                        Timber.w(e)
                    } catch (e: Exception) {
                        error = ERROR_OTHER
                        Timber.e(e)
                    }
                    if (forceCheck) {
                        if (mgskPth == null) {
                            mgskVerCode = 0
                        }
                    }
                    if (mgskPth != null) {
                        if (MainApplication.forceDebugLogging) {
                            Timber.i("Magisk path async: %s", mgskPth)
                        }
                        MainApplication.setHasGottenRootAccess(true)
                        callback.onPathReceived(mgskPth)
                    } else {
                        MainApplication.setHasGottenRootAccess(false)
                        callback.onFailure(error)
                    }
                }
            }
            thread.start()
        }

        private fun tryGetMagiskPath(forceCheck: Boolean): String? {
            var mgskPth = mgskPth
            val mgskVerCode: Int
            var hsRmdsk = hsRmdsk
            if (mgskPth != null && !forceCheck) return mgskPth
            val output = ArrayList<String>()
            if (Shell.isAppGrantedRoot() == null || !Shell.isAppGrantedRoot()!!) {
                // if Shell.isAppGrantedRoot() == null loop until it's not null
                return if (Shell.isAppGrantedRoot() == null) {
                    Thread.sleep(150)
                    tryGetMagiskPath(forceCheck)
                } else {
                    null
                }
            }
            try {
                if (Shell.cmd(
                        "if grep ' / ' /proc/mounts | grep -q '/dev/root' &> /dev/null; " + "then echo true; else echo false; fi"
                    ).to(output).exec().isSuccess
                ) {
                    if (output.size != 0) {
                        hsRmdsk = "false" == output[0] || "true".equals(
                            System.getProperty("ro.build.ab_update"), ignoreCase = true
                        )
                    }
                    Companion.hsRmdsk = hsRmdsk
                } else {
                    if (MainApplication.forceDebugLogging) {
                        Timber.e("Failed to check for ramdisk")
                    }
                    return null
                }
                if (MainApplication.forceDebugLogging) {
                    Timber.i("Found ramdisk: %s", output[0])
                    if (MainApplication.forceDebugLogging) Timber.i(
                        "Searching for Magisk path. Current path: %s",
                        mgskPth
                    )
                }
                // reset output
                output.clear()
                // try to use magisk --path. if that fails, check for /data/adb/ksu for kernelsu support
                if (Shell.cmd("magisk --path", "su -V").to(output)
                        .exec().isSuccess && output[0].isNotEmpty() && !output[0].contains(
                        "not found"
                    )
                ) {
                    mgskPth = output[0]
                    if (MainApplication.forceDebugLogging) {
                        Timber.i("Magisk path 1: %s", mgskPth)
                    }
                } else if (Shell.cmd(
                        "if [ -d /data/adb/ksu ]; then echo true; else echo false; fi",
                        "/data/adb/ksud -V"
                    ).to(
                        output
                    ).exec().isSuccess && "true" == output[0] && output[1].isNotEmpty() && !output[1].contains(
                        "not found", true)
                ) {
                    if (MainApplication.forceDebugLogging) {
                        Timber.i("Kernelsu detected")
                    }
                    mgskPth = "/data/adb"
                    isKsu = true
                    // if analytics enabled, set breadcrumb for countly
                    if (MainApplication.analyticsAllowed()) {
                        Countly.sharedInstance().crashes().addCrashBreadcrumb("ksu detected")
                    }
                    if (MainApplication.forceDebugLogging) {
                        Timber.e("[ANOMALY] Kernelsu not detected but /data/adb/ksu exists - maybe outdated?")
                    }
                    return mgskPth
                } else {
                    if (MainApplication.forceDebugLogging) {
                        Timber.e("Failed to get Magisk path")
                    }
                    return null
                }
                if (MainApplication.forceDebugLogging) Timber.i("Magisk runtime path: %s", mgskPth)
                mgskVerCode = output[1].toInt()
                if (MainApplication.forceDebugLogging) Timber.i(
                    "Magisk version code: %s",
                    mgskVerCode
                )
                if (mgskVerCode >= Constants.MAGISK_VER_CODE_FLAT_MODULES && mgskVerCode < Constants.MAGISK_VER_CODE_PATH_SUPPORT && (mgskPth.isEmpty() || !File(
                        mgskPth
                    ).exists())
                ) {
                    mgskPth = "/sbin"
                }
                if (mgskPth.isNotEmpty() && existsSU(File(mgskPth))) {
                    Companion.mgskPth = mgskPth
                } else {
                    Timber.e("Failed to get Magisk path (Got $mgskPth)")
                    mgskPth = null
                }
                // if mgskPth is null, but we're granted root, log an error
                if (mgskPth == null && Shell.isAppGrantedRoot() == true) {
                    Timber.e("[ANOMALY] Failed to get Magisk path but granted root")
                }
                Companion.mgskVerCode = mgskVerCode
                return mgskPth
            } catch (ignored: Exception) {
                // work around edge case
                return if (tries <= 10) {
                    tries++
                    try {
                        Thread.sleep(tries * 50L)
                    } catch (e: InterruptedException) {
                        Timber.e(e)
                    }
                    tryGetMagiskPath(true)
                } else {
                    null
                }
            }
        }
    }
}
