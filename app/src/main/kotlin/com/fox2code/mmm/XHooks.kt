/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.annotation.Keep
import com.fox2code.mmm.manager.ModuleManager
import com.fox2code.mmm.repo.RepoManager

/**
 * Class made to expose some manager functions to xposed modules.
 * It will not be obfuscated on release builds
 *
 * TODO: Evaluate usage and deprecate if not needed
 */
@Suppress("UNUSED_PARAMETER")
@Keep
enum class XHooks {
    ;

    companion object {
        @Keep
        fun onRepoManagerInitialize() {
            // Call addXRepo here if you are an XPosed module
        }

        @Keep
        fun onRepoManagerInitialized() {
        }

        @Keep
        fun isModuleActive(moduleId: String?): Boolean {
            return ModuleManager.isModuleActive(moduleId!!)
        }

        @Keep
        @Throws(PackageManager.NameNotFoundException::class)
        fun checkConfigTargetExists(context: Context, packageName: String, config: String) {
            if ("org.lsposed.manager" == config && "org.lsposed.manager" == packageName &&
                (isModuleActive("riru_lsposed") || isModuleActive("zygisk_lsposed"))
            ) return  // Skip check for lsposed as it is probably injected into the system.
            context.packageManager.getPackageInfo(packageName, 0)
        }

        @Suppress("UNUSED_PARAMETER")
        @Keep
        fun getConfigIntent(context: Context, packageName: String?, config: String?): Intent? {
            return context.packageManager.getLaunchIntentForPackage(packageName!!)
        }

        @Keep
        fun onWebViewInitialize(webView: WebView?, allowInstall: Boolean) {
            if (webView == null) throw NullPointerException("WebView is null!")
        }

        @Keep
        fun addXRepo(url: String?, fallbackName: String?): XRepo {
            return url?.let { RepoManager.iNSTANCE_UNSAFE?.addOrGet(it, fallbackName) }!!
        }

        @Keep
        fun getXRepo(url: String?): XRepo {
            return RepoManager.iNSTANCE_UNSAFE?.get(url)
                ?: throw NullPointerException("Repo not found!")
        }

        @get:Keep
        val xRepos: Collection<XRepo>
            get() = RepoManager.iNSTANCE_UNSAFE!!.xRepos
    }
}