/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.module

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.fox2code.mmm.AppUpdateManager
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekHasRamdisk
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.repo.RepoManager
import timber.log.Timber
import java.util.EnumSet

class ModuleViewListBuilder(private val activity: Activity) {
    private val notifications = EnumSet.noneOf(
        NotificationType::class.java
    )
    private val mappedModuleHolders = HashMap<String, ModuleHolder>()
    private val updateLock = Any()
    private val queryLock = Any()
    private var query = ""
    private var updating = false
    private var tries = 0
    private var moduleSorter: ModuleSorter = ModuleSorter.UPDATE
    private var updateInsets = RUNNABLE
    fun addNotification(notificationType: NotificationType?) {
        if (notificationType == null) {
            Timber.w("addNotification(null) called!")
            return
        } else {
            if (MainApplication.forceDebugLogging) Timber.i(
                "addNotification(%s) called",
                notificationType
            )
        }
        synchronized(updateLock) { notifications.add(notificationType) }
    }

    fun appendInstalledModules() {
        if (MainApplication.forceDebugLogging) Timber.i("appendInstalledModules() called")
        synchronized(updateLock) {
            for (moduleHolder in mappedModuleHolders.values) {
                if (MainApplication.forceDebugLogging) Timber.i(
                    "zeroing module %s",
                    moduleHolder.moduleInfo?.id
                )
                moduleHolder.moduleInfo = null
            }
            val moduleManager = instance
            moduleManager?.runAfterScan {
                if (MainApplication.forceDebugLogging) Timber.i(
                    "A0: runAfterScan %s",
                    moduleManager.modules.size
                )
                if (MainApplication.forceDebugLogging) Timber.i(
                    "A1: %s",
                    moduleManager.modules.size
                )
                for (moduleInfo in moduleManager.modules.values) {
                    // add the local module to the list in MainActivity
                    MainActivity.localModuleInfoList += moduleInfo
                    var moduleHolder = mappedModuleHolders[moduleInfo.id]
                    if (moduleHolder == null) {
                        mappedModuleHolders[moduleInfo.id] = ModuleHolder(moduleInfo.id).also {
                            moduleHolder = it
                        }
                    }
                    moduleHolder!!.moduleInfo = moduleInfo
                }
            }
        }
    }

    fun appendRemoteModules() {
        if (MainApplication.forceDebugLogging) {
            Timber.i("appendRemoteModules() called")
        }
        synchronized(updateLock) {
            if (MainApplication.forceDebugLogging) Timber.i("appendRemoteModules() started")
            val startTime = System.currentTimeMillis()
            val showIncompatible = MainApplication.isShowIncompatibleModules
            for (moduleHolder in mappedModuleHolders.values) {
                moduleHolder.repoModule = null
            }
            val repoManager = RepoManager.getINSTANCE()
            repoManager?.runAfterUpdate {
                if (MainApplication.forceDebugLogging) Timber.i("A2: %s", repoManager.modules.size)
                val no32bitSupport = Build.SUPPORTED_32_BIT_ABIS.isEmpty()
                try {
                    for (repoModule in repoManager.modules.values) {
                        // add the remote module to the list in MainActivity
                        MainActivity.onlineModuleInfoList += repoModule
                        // if repoData is null, something is wrong
                        @Suppress("SENSELESS_COMPARISON") if (repoModule.repoData == null) {
                            Timber.w("RepoData is null for module %s", repoModule.id)
                            continue
                        }
                        if (!repoModule.repoData.isEnabled) {
                            if (MainApplication.forceDebugLogging) Timber.i(
                                "Repo %s is disabled, skipping module %s",
                                repoModule.repoData.preferenceId,
                                repoModule.id
                            )
                            continue
                        }
                        val moduleInfo = repoModule.moduleInfo
                        if (!showIncompatible && (moduleInfo.minApi > Build.VERSION.SDK_INT || moduleInfo.maxApi != 0 && moduleInfo.maxApi < Build.VERSION.SDK_INT || peekMagiskPath() != null) && (!InstallerInitializer.isKsu && repoModule.moduleInfo.minMagisk > peekMagiskVersion()) || no32bitSupport && (AppUpdateManager.getFlagsForModule(
                                repoModule.id
                            ) and AppUpdateManager.FLAG_COMPAT_NEED_32BIT) != 0 || repoModule.moduleInfo.needRamdisk && !peekHasRamdisk()
                        ) continue  // Skip adding incompatible modules
                        var moduleHolder = mappedModuleHolders[repoModule.id]
                        if (moduleHolder == null) {
                            mappedModuleHolders[repoModule.id] = ModuleHolder(repoModule.id).also {
                                moduleHolder = it
                            }
                        }
                        moduleHolder!!.repoModule = repoModule
                        // check if local module is installed
                        // iterate over MainActivity.localModuleInfoList until we hit the module with the same id
                        for (localModuleInfo in MainActivity.localModuleInfoList) {
                            if (localModuleInfo.id == repoModule.id) {
                                moduleHolder!!.moduleInfo = localModuleInfo
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "appendRemoteModules() failed")
                    // retry up to five times, waiting i * 100ms between each try
                    if (tries < 5) {
                        tries++
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "appendRemoteModules() retrying in %dms",
                            tries * 100
                        )
                        Handler(Looper.getMainLooper()).postDelayed({
                            appendRemoteModules()
                        }, tries * 100.toLong())
                    } else {
                        Timber.e(e, "appendRemoteModules() failed after %d tries", tries)
                        tries = 0
                    }
                }
                if (MainApplication.forceDebugLogging) Timber.i(
                    "appendRemoteModules() finished in %dms",
                    System.currentTimeMillis() - startTime
                )
            }
        }
    }

    private fun matchFilter(moduleHolder: ModuleHolder): Boolean {
        val moduleInfo = moduleHolder.mainModuleInfo
        val query = query
        val idLw = moduleInfo.id.lowercase()
        var nameLw: String? = null
        if (moduleInfo.name != null) {
            nameLw = moduleInfo.name!!.lowercase()
        }
        val authorLw = if (moduleInfo.author == null) "" else moduleInfo.author!!.lowercase()
        if (query.isEmpty() || query == idLw || query == nameLw || query == authorLw) {
            moduleHolder.filterLevel = 0 // Lower = better
            return true
        }
        if (nameLw != null && (idLw.contains(query) || nameLw.contains(query))) {
            moduleHolder.filterLevel = 1
            return true
        }
        if (authorLw.contains(query) || moduleInfo.description != null && moduleInfo.description!!.lowercase()
                .contains(query)
        ) {
            moduleHolder.filterLevel = 2
            return true
        }
        moduleHolder.filterLevel = 3
        return false
    }

    fun applyTo(moduleList: RecyclerView, moduleViewAdapter: ModuleViewAdapter) {
        if (updating) return
        updating = true
        instance!!.afterScan()
        RepoManager.getINSTANCE()!!.afterUpdate()
        val moduleHolders: ArrayList<ModuleHolder>
        val newNotificationsLen: Int
        val first: Boolean
        try {
            synchronized(updateLock) {

                // Build start
                moduleHolders = ArrayList(
                    64.coerceAtMost(mappedModuleHolders.size + 5)
                )
                var special = 0
                // add notifications
                val notificationTypeIterator = notifications.iterator()
                while (notificationTypeIterator.hasNext()) {
                    val notificationType = notificationTypeIterator.next()
                    if (notificationType.shouldRemove()) {
                        notificationTypeIterator.remove()
                    } else {
                        if (notificationType.special) special++
                        moduleHolders.add(ModuleHolder(notificationType))
                    }
                }
                first = moduleViewAdapter.moduleHolders.isEmpty()
                newNotificationsLen = notifications.size + 1 - special
                val headerTypes = EnumSet.of(
                    ModuleHolder.Type.SEPARATOR,
                    ModuleHolder.Type.NOTIFICATION,
                    ModuleHolder.Type.FOOTER
                )
                val moduleHolderIterator = mappedModuleHolders.values.iterator()
                synchronized(queryLock) {
                    while (moduleHolderIterator.hasNext()) {
                        val moduleHolder = moduleHolderIterator.next()
                        if (moduleHolder.shouldRemove()) {
                            moduleHolderIterator.remove()
                        } else {
                            val type = moduleHolder.type
                            if (matchFilter(moduleHolder)) {
                                if (headerTypes.add(type)) {
                                    val separator = ModuleHolder(type)
                                    if (type === ModuleHolder.Type.INSTALLABLE) {
                                        val moduleSorter = moduleSorter
                                        separator.filterLevel = this.moduleSorter.icon
                                        separator.onClickListener =
                                            View.OnClickListener { _: View? ->
                                                if (updating || this.moduleSorter !== moduleSorter) return@OnClickListener   // Do not allow spams calls
                                                this.moduleSorter = this.moduleSorter.next()!!
                                                Thread(
                                                    { // Apply async
                                                        applyTo(moduleList, moduleViewAdapter)
                                                    }, "Sorter apply Thread"
                                                ).start()
                                            }
                                    }
                                    moduleHolders.add(separator)
                                }
                                moduleHolders.add(moduleHolder)
                            }
                        }
                    }
                }
                moduleHolders.sortWith(moduleSorter)
                // Header is always first
                //moduleHolders.add(0, headerFooter[0] =
                //        new ModuleHolder(this.headerPx / 2, true));
                // Footer is always last
                //moduleHolders.add(headerFooter[1] =
                //        new ModuleHolder(this.footerPx * 2, false));
                if (MainApplication.forceDebugLogging) Timber.i("Got " + moduleHolders.size + " entries!")
            }
        } finally {
            updating = false
        }
        activity.runOnUiThread {
            updateInsets = RUNNABLE
            val oldNotifications = EnumSet.noneOf(
                NotificationType::class.java
            )
            val isTop = first || !moduleList.canScrollVertically(-1)
            val isBottom = !isTop && !moduleList.canScrollVertically(1)
            var oldNotificationsLen = 0
            var oldOfflineModulesLen = 0
            for (moduleHolder in moduleViewAdapter.moduleHolders) {
                val notificationType = moduleHolder.notificationType
                if (notificationType != null) {
                    oldNotifications.add(notificationType)
                    if (!notificationType.special) oldNotificationsLen++
                } else if (moduleHolder.footerPx != -1 && moduleHolder.filterLevel == 1) oldNotificationsLen++ // Fix header
                if (moduleHolder.separator === ModuleHolder.Type.INSTALLABLE) break
                oldOfflineModulesLen++
            }
            oldOfflineModulesLen -= oldNotificationsLen
            var newOfflineModulesLen = 0
            for (moduleHolder in moduleHolders) {
                if (moduleHolder.separator === ModuleHolder.Type.INSTALLABLE) break
                newOfflineModulesLen++
            }
            newOfflineModulesLen -= newNotificationsLen
            moduleViewAdapter.moduleHolders.size
            val newLen = moduleHolders.size
            val oldLen = moduleViewAdapter.moduleHolders.size
            moduleViewAdapter.moduleHolders.clear()
            moduleViewAdapter.moduleHolders.addAll(moduleHolders)
            if (oldNotificationsLen != newNotificationsLen || oldNotifications != notifications) {
                notifySizeChanged(
                    moduleViewAdapter, 0, oldNotificationsLen, newNotificationsLen
                )
            } else {
                notifySizeChanged(moduleViewAdapter, 0, 1, 1)
            }
            if (newLen - newNotificationsLen == 0) {
                notifySizeChanged(
                    moduleViewAdapter, newNotificationsLen, oldLen - oldNotificationsLen, 0
                )
            } else {
                notifySizeChanged(
                    moduleViewAdapter,
                    newNotificationsLen,
                    oldOfflineModulesLen,
                    newOfflineModulesLen
                )
                notifySizeChanged(
                    moduleViewAdapter,
                    newNotificationsLen + newOfflineModulesLen,
                    oldLen - oldNotificationsLen - oldOfflineModulesLen,
                    newLen - newNotificationsLen - newOfflineModulesLen
                )
            }
            if (isTop) moduleList.scrollToPosition(0)
            if (isBottom) moduleList.scrollToPosition(newLen)
            updateInsets = Runnable {
                notifySizeChanged(moduleViewAdapter, 0, 1, 1)
                notifySizeChanged(
                    moduleViewAdapter, moduleHolders.size, 1, 1
                )
            }
        }
    }

    fun setQueryChange(query: String?): Boolean {
        synchronized(queryLock) {
            val newQuery = query?.trim { it <= ' ' }?.lowercase() ?: ""
            if (MainApplication.forceDebugLogging) Timber.i("Query change " + this.query + " -> " + newQuery)
            if (this.query == newQuery) return false
            this.query = newQuery
        }
        return true
    }

    companion object {
        private val RUNNABLE = Runnable {}
        private fun notifySizeChanged(
            moduleViewAdapter: ModuleViewAdapter, index: Int, oldLen: Int, newLen: Int
        ) {
            // if (MainApplication.forceDebugLogging) Timber.i("A: " + index + " " + oldLen + " " + newLen);
            if (oldLen == newLen) {
                if (newLen != 0) moduleViewAdapter.notifyItemRangeChanged(index, newLen)
            } else if (oldLen < newLen) {
                if (oldLen != 0) moduleViewAdapter.notifyItemRangeChanged(index, oldLen)
                moduleViewAdapter.notifyItemRangeInserted(
                    index + oldLen, newLen - oldLen
                )
            } else {
                if (newLen != 0) moduleViewAdapter.notifyItemRangeChanged(index, newLen)
                moduleViewAdapter.notifyItemRangeRemoved(
                    index + newLen, oldLen - newLen
                )
            }
        }
    }
}