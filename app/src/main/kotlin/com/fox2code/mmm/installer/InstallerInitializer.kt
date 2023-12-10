/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.installer

import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.NotificationType
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import timber.log.Timber

@Suppress("unused")
class InstallerInitializer {
    interface Callback {
        fun onPathReceived(path: String?)
        fun onFailure(error: Int)
    }

    companion object {
        var isKsu: Boolean = false
        private var mgskPth: String? = null
        private var verCode = 0
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
            return if (mgskPth == null) null else "/data/adb/modules"
        }

        fun peekMagiskVersion(): Int {
            return verCode
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
                            verCode = 0
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
                    Companion.hsRmdsk = false
                }
                output.clear()
                if (Shell.cmd("su -v").to(output).exec().isSuccess) {
                    Timber.i("SU version small: %s", output[0])
                    if (output.size != 0) {
                        // try su -V
                        if (Shell.cmd("su -V").exec().isSuccess) {
                            val suVer = Shell.cmd("su -V").exec().out
                                Timber.i("SU version: %s", suVer[0])
                                // use regex to get version code
                                val matcher2 = Regex("(\\d+)").find(suVer[0])
                                if (matcher2 != null) {
                                    mgskVerCode = matcher2.groupValues[1].toInt()
                                    if (mgskVerCode > verCode) {
                                        verCode = mgskVerCode
                                        Timber.i("SU version: %d", mgskVerCode)
                                    }
                                } else {
                                    if (MainApplication.forceDebugLogging) {
                                        Timber.e("Failed to get su version: matcher2 is null")
                                    }
                                    verCode = 0
                                }
                        } else {
                            if (MainApplication.forceDebugLogging) {
                                Timber.e("Failed to get su version: su -V: unsuccessful")
                            }
                            verCode = 0
                            return null
                        }
                    } else {
                        if (MainApplication.forceDebugLogging) {
                            Timber.e("Failed to get su version: su -v: output size is 0")
                        }
                        verCode = 0
                    }
                    mgskPth = "/data/adb" // hardcoded path. all modern versions of ksu and magisk use this path
                    if (MainApplication.forceDebugLogging) {
                        Timber.i("Magisk path: %s", mgskPth)
                    }
                    Companion.mgskPth = mgskPth
                    val suVer2 = Shell.cmd("su -v").exec().out
                    // if output[0] contains kernelsu, then it's ksu. if it contains magisk, then it's magisk. otherwise, it's something we don't know and we return null
                    if (suVer2[0].contains("kernelsu", true)) {
                        isKsu = true
                        if (MainApplication.forceDebugLogging) {
                            Timber.i("SU version: ksu")
                        }
                    } else if (suVer2[0].contains("magisk", true)) {
                        isKsu = false
                        if (MainApplication.forceDebugLogging) {
                            Timber.i("SU version: magisk")
                        }
                    } else {
                        if (MainApplication.forceDebugLogging) {
                            Timber.e("Failed to get su version: unknown su")
                        }
                        verCode = 0
                        return null
                    }
                    return mgskPth
                } else {
                    if (MainApplication.forceDebugLogging) {
                        Timber.e("Failed to get su version")
                    }
                    verCode = 0
                    return null
                }
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
