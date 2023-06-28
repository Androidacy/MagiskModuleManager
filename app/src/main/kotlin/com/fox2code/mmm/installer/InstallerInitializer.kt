/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.installer

import android.content.Context
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.utils.io.Files.Companion.existsSU
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import timber.log.Timber
import java.io.File

@Suppress("unused")
class InstallerInitializer : Shell.Initializer() {
    interface Callback {
        fun onPathReceived(path: String?)
        fun onFailure(error: Int)
    }

    override fun onInit(context: Context, shell: Shell): Boolean {
        return if (!shell.isRoot) true else shell.newJob().add("export ASH_STANDALONE=1")
            .exec().isSuccess
    }

    companion object {
        private val MAGISK_SBIN = File("/sbin/magisk")
        private val MAGISK_SYSTEM = File("/system/bin/magisk")
        private val MAGISK_SYSTEM_EX = File("/system/xbin/magisk")
        private val HAS_MAGISK =
            MAGISK_SBIN.exists() || MAGISK_SYSTEM.exists() || MAGISK_SYSTEM_EX.exists()
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
                if (!HAS_MAGISK) {
                    return NotificationType.NO_MAGISK
                } else if (hasRoot !== java.lang.Boolean.TRUE) {
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
            val mgskPth = mgskPth
            val thread: Thread = object : Thread("Magisk GetPath Thread") {
                override fun run() {
                    if (mgskPth != null && !forceCheck) {
                        callback.onPathReceived(mgskPth)
                        return
                    }
                    var error: Int
                    @Suppress("NAME_SHADOWING") var mgskPth: String? = null
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
                        Companion.mgskPth = mgskPth
                        if (mgskPth == null) {
                            mgskVerCode = 0
                        }
                    }
                    if (mgskPth != null) {
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
            try {
                if (!Shell.cmd(
                        "if grep ' / ' /proc/mounts | grep -q '/dev/root' &> /dev/null; " + "then echo true; else echo false; fi",
                        "magisk -V",
                        "magisk --path"
                    ).to(output).exec().isSuccess
                ) {
                    if (output.size != 0) {
                        hsRmdsk = "false" == output[0] || "true".equals(
                            System.getProperty("ro.build.ab_update"), ignoreCase = true
                        )
                    }
                    Companion.hsRmdsk = hsRmdsk
                    return null
                }
                mgskPth = if (output.size < 3) "" else output[2]
                Timber.i("Magisk runtime path: %s", mgskPth)
                mgskVerCode = output[1].toInt()
                Timber.i("Magisk version code: %s", mgskVerCode)
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
                Companion.mgskVerCode = mgskVerCode
                return mgskPth
            } catch (ignored: Exception) {
                return if (tries < 5) {
                    tries++
                    tryGetMagiskPath(true)
                } else {
                    null
                }
            }
        }
    }
}
