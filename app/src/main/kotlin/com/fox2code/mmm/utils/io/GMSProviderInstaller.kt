/*
 * Copyright (c) 2021-2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.utils.io

import android.content.Context
import android.content.pm.PackageManager
import com.fox2code.mmm.MainApplication
import timber.log.Timber

/**
 * Open implementation of ProviderInstaller.installIfNeeded
 * (Compatible with MicroG even without signature spoofing)
 */
// Note: This code is MIT because I took it from another unpublished project I had
// I might upstream this to MicroG at some point
enum class GMSProviderInstaller {
    ;

    companion object {
        private var called = false

        fun installIfNeeded(context: Context?) {
            if (context == null) {
                throw NullPointerException("Context must not be null")
            }
            if (called) return
            called = true
            try {
                // Trust default GMS implementation
                val remote = context.createPackageContext(
                    "com.google.android.gms",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                val cl =
                    remote.classLoader.loadClass("com.google.android.gms.common.security.ProviderInstallerImpl")
                cl.getDeclaredMethod("insertProvider", Context::class.java).invoke(null, remote)
                if (MainApplication.forceDebugLogging) Timber.i("Installed GMS security providers!")
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.w("No GMS Implementation are installed on this device")
            } catch (e: Exception) {
                Timber.w(e)
            }
        }
    }
}