@file:Suppress("unused")

package com.fox2code.mmm.manager

import android.content.SharedPreferences
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekModulesPath
import com.fox2code.mmm.utils.SyncManager
import com.fox2code.mmm.utils.io.PropUtils
import com.fox2code.mmm.utils.realm.ModuleListCache
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuFileInputStream
import io.realm.Realm
import io.realm.RealmConfiguration
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Objects

class ModuleManager private constructor() : SyncManager() {
    private val moduleInfos: HashMap<String, LocalModuleInfo> = HashMap()
    private val bootPrefs: SharedPreferences = MainApplication.bootSharedPreferences!!
    private var updatableModuleCount = 0

    override fun scanInternal(updateListener: UpdateListener) {
        // if last_shown_setup is not "v2", then refuse to continue
        if (MainApplication.getSharedPreferences("mmm")!!.getString("last_shown_setup", "") != "v2") {
            return
        }
        val firstScan = bootPrefs.getBoolean("mm_first_scan", true)
        val editor = if (firstScan) bootPrefs.edit() else null
        for (v in moduleInfos.values) {
            v.flags = v.flags or FLAG_MM_UNPROCESSED
            v.flags = v.flags and FLAGS_KEEP_INIT
            v.name = v.id
            v.version = null
            v.versionCode = 0
            v.author = null
            v.description = ""
            v.support = null
            v.config = null
        }
        val modulesPath = peekModulesPath()
        val modules = SuFile("/data/adb/modules").list()
        val needFallback =
            FORCE_NEED_FALLBACK || modulesPath == null || !SuFile(modulesPath).exists()
        if (!FORCE_NEED_FALLBACK && needFallback) {
            Timber.e("using fallback instead.")
        }
        if (BuildConfig.DEBUG) Timber.d("Scan")
        val modulesList = StringBuilder()
        if (modules != null) {
            for (module in modules) {
                if (!SuFile("/data/adb/modules/$module").isDirectory) continue  // Ignore non directory files inside modules folder
                var moduleInfo = moduleInfos[module]
                // next, merge the module info with a record from ModuleListCache if it exists
                var realmConfiguration: RealmConfiguration?
                // get all dirs under the realms/repos/ dir under app's data dir
                val cacheRoot =
                    File(MainApplication.INSTANCE!!.getDataDirWithPath("realms/repos/").toURI())
                var moduleListCache: ModuleListCache?
                for (dir in Objects.requireNonNull<Array<File>>(cacheRoot.listFiles())) {
                    if (dir.isDirectory) {
                        // if the dir name matches the module name, use it as the cache dir
                        val tempCacheRoot = File(dir.toString())
                        Timber.d("Looking for cache in %s", tempCacheRoot)
                        realmConfiguration =
                            RealmConfiguration.Builder().name("ModuleListCache.realm")
                                .encryptionKey(MainApplication.INSTANCE!!.key).schemaVersion(1)
                                .deleteRealmIfMigrationNeeded().allowWritesOnUiThread(true)
                                .allowQueriesOnUiThread(true).directory(tempCacheRoot).build()
                        val realm = Realm.getInstance(realmConfiguration!!)
                        Timber.d(
                            "Looking for cache for %s out of %d", module, realm.where(
                                ModuleListCache::class.java
                            ).count()
                        )
                        moduleListCache =
                            realm.where(ModuleListCache::class.java).equalTo("codename", module)
                                .findFirst()
                        Timber.d("Found cache for %s", module)
                        // get module info from cache
                        if (moduleInfo == null) {
                            moduleInfo = LocalModuleInfo(module)
                        }
                        if (moduleListCache != null) {
                            moduleInfo.name =
                                if (moduleListCache.name != "") moduleListCache.name else module
                            moduleInfo.description =
                                if (moduleListCache.description != "") moduleListCache.description else moduleInfo.description
                            moduleInfo.author =
                                if (moduleListCache.author != "") moduleListCache.author else moduleInfo.author
                            moduleInfo.safe = moduleListCache.isSafe == true
                            moduleInfo.support =
                                if (moduleListCache.support != "") moduleListCache.support else null
                            moduleInfo.donate =
                                if (moduleListCache.donate != "") moduleListCache.donate else null
                            moduleInfo.flags = moduleInfo.flags or FLAG_MM_REMOTE_MODULE
                            moduleInfos[module] = moduleInfo
                        }
                        realm.close()
                        break
                    }
                }
                if (moduleInfo == null) {
                    moduleInfo = LocalModuleInfo(module)
                    moduleInfos[module] = moduleInfo
                    // This should not really happen, but let's handles theses cases anyway
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_UPDATING_ONLY
                }
                moduleInfo.flags = moduleInfo.flags and FLAGS_RESET_UPDATE.inv()
                if (SuFile("/data/adb/modules/$module/disable").exists()) {
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_DISABLED
                } else if (firstScan && needFallback) {
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_ACTIVE
                    editor!!.putBoolean("module_" + moduleInfo.id + "_active", true)
                }
                if (SuFile("/data/adb/modules/$module/remove").exists()) {
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_UNINSTALLING
                }
                if (firstScan && !needFallback && SuFile(
                        modulesPath,
                        module
                    ).exists() || bootPrefs.getBoolean("module_" + moduleInfo.id + "_active", false)
                ) {
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_ACTIVE
                    if (firstScan) {
                        editor!!.putBoolean("module_" + moduleInfo.id + "_active", true)
                    }
                } else if (!needFallback) {
                    moduleInfo.flags = moduleInfo.flags and ModuleInfo.FLAG_MODULE_ACTIVE.inv()
                }
                if (moduleInfo.flags and ModuleInfo.FLAGS_MODULE_ACTIVE != 0 && (SuFile(
                        "/data/adb/modules/$module/system"
                    ).exists() || SuFile("/data/adb/modules/$module/vendor").exists() || SuFile("/data/adb/modules/$module/zygisk").exists() || SuFile(
                        "/data/adb/modules/$module/riru"
                    ).exists())
                ) {
                    moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_HAS_ACTIVE_MOUNT
                }
                try {
                    PropUtils.readProperties(
                        moduleInfo,
                        "/data/adb/modules/$module/module.prop",
                        true
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Timber.d(e)
                    moduleInfo.flags = moduleInfo.flags or FLAG_MM_INVALID
                }
                // append moduleID:moduleName to the list
                modulesList.append(moduleInfo.id).append(":").append(moduleInfo.versionCode)
                    .append(",")
            }
        }
        if (modulesList.isNotEmpty()) {
            modulesList.deleteCharAt(modulesList.length - 1)
        }
        // send list to matomo
        TrackHelper.track().event("installed_modules", modulesList.toString())
            .with(MainApplication.INSTANCE!!.tracker)
        if (BuildConfig.DEBUG) Timber.d("Scan update")
        val modulesUpdate = SuFile("/data/adb/modules_update").list()
        if (modulesUpdate != null) {
            for (module in modulesUpdate) {
                if (!SuFile("/data/adb/modules_update/$module").isDirectory) continue  // Ignore non directory files inside modules folder
                if (BuildConfig.DEBUG) Timber.d(module)
                var moduleInfo = moduleInfos[module]
                if (moduleInfo == null) {
                    moduleInfo = LocalModuleInfo(module)
                    moduleInfos[module] = moduleInfo
                }
                moduleInfo.flags = moduleInfo.flags and FLAGS_RESET_UPDATE.inv()
                moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_UPDATING
                try {
                    PropUtils.readProperties(
                        moduleInfo,
                        "/data/adb/modules_update/$module/module.prop",
                        true
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Timber.d(e)
                    moduleInfo.flags = moduleInfo.flags or FLAG_MM_INVALID
                }
            }
        }
        if (BuildConfig.DEBUG) Timber.d("Finalize scan")
        updatableModuleCount = 0
        val moduleInfoIterator = moduleInfos.values.iterator()
        while (moduleInfoIterator.hasNext()) {
            val moduleInfo = moduleInfoIterator.next()
            if (BuildConfig.DEBUG) Timber.d(moduleInfo.id)
            if (moduleInfo.flags and FLAG_MM_UNPROCESSED != 0) {
                moduleInfoIterator.remove()
                continue  // Don't process fallbacks if unreferenced
            }
            if (moduleInfo.updateJson != null && moduleInfo.flags and FLAG_MM_REMOTE_MODULE == 0) {
                updatableModuleCount++
            } else {
                moduleInfo.updateVersion = null
                moduleInfo.updateVersionCode = Long.MIN_VALUE
                moduleInfo.updateZipUrl = null
                moduleInfo.updateChangeLog = ""
            }
            if (moduleInfo.name == null || moduleInfo.name == moduleInfo.id) {
                moduleInfo.name =
                    moduleInfo.id[0].uppercaseChar().toString() + moduleInfo.id.substring(1)
                        .replace('_', ' ')
            }
            if (moduleInfo.version == null || moduleInfo.version!!.trim { it <= ' ' }.isEmpty()) {
                moduleInfo.version = "v" + moduleInfo.versionCode
            }
            moduleInfo.verify()
        }
        if (firstScan) {
            editor!!.putBoolean("mm_first_scan", false)
            editor.apply()
        }
    }

    val modules: HashMap<String, LocalModuleInfo>
        get() {
            afterScan()
            return moduleInfos
        }

    @Suppress("unused")
    fun getUpdatableModuleCount(): Int {
        afterScan()
        return updatableModuleCount
    }

    @Suppress("unused")
    fun setEnabledState(moduleInfo: ModuleInfo, checked: Boolean): Boolean {
        if (moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING) && !checked) return false
        val disable = SuFile("/data/adb/modules/" + moduleInfo.id + "/disable")
        if (checked) {
            if (disable.exists() && !disable.delete()) {
                moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_DISABLED
                return false
            }
            moduleInfo.flags = moduleInfo.flags and ModuleInfo.FLAG_MODULE_DISABLED.inv()
        } else {
            if (!disable.exists() && !disable.createNewFile()) {
                return false
            }
            moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_DISABLED
        }
        return true
    }

    @Suppress("unused")
    fun setUninstallState(moduleInfo: ModuleInfo, checked: Boolean): Boolean {
        if (checked && moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_UPDATING)) return false
        val disable = SuFile("/data/adb/modules/" + moduleInfo.id + "/remove")
        if (checked) {
            if (!disable.exists() && !disable.createNewFile()) {
                return false
            }
            moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_UNINSTALLING
        } else {
            if (disable.exists() && !disable.delete()) {
                moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MODULE_UNINSTALLING
                return false
            }
            moduleInfo.flags = moduleInfo.flags and ModuleInfo.FLAG_MODULE_UNINSTALLING.inv()
        }
        return true
    }

    fun masterClear(moduleInfo: ModuleInfo): Boolean {
        if (moduleInfo.hasFlag(ModuleInfo.FLAG_MODULE_HAS_ACTIVE_MOUNT)) return false
        val escapedId =
            moduleInfo.id.replace("\\", "\\\\").replace("\"", "\\\"").replace(" ", "\\ ")
        try { // Check for module that declare having file outside their own folder.
            BufferedReader(
                InputStreamReader(
                    SuFileInputStream.open("/data/adb/modules/." + moduleInfo.id + "-files"),
                    StandardCharsets.UTF_8
                )
            ).use { bufferedReader ->
                var line: String
                while (bufferedReader.readLine().also { line = it } != null) {
                    line = line.trim { it <= ' ' }.replace(' ', '.')
                    if (!line.startsWith("/data/adb/") || line.contains("*") || line.contains("/../") || line.endsWith(
                            "/.."
                        ) || line.startsWith("/data/adb/modules") || line == "/data/adb/magisk.db"
                    ) continue
                    line = line.replace("\\", "\\\\").replace("\"", "\\\"")
                    Shell.cmd("rm -rf \"$line\"").exec()
                }
            }
        } catch (ignored: IOException) {
        }
        Shell.cmd("rm -rf /data/adb/modules/$escapedId/").exec()
        Shell.cmd("rm -f /data/adb/modules/.$escapedId-files").exec()
        Shell.cmd("rm -rf /data/adb/modules_update/$escapedId/").exec()
        moduleInfo.flags = ModuleInfo.FLAG_METADATA_INVALID
        return true
    }

    companion object {
        // New method is not really effective, this flag force app to use old method
        const val FORCE_NEED_FALLBACK = true
        private const val FLAG_MM_INVALID = ModuleInfo.FLAG_METADATA_INVALID
        private const val FLAG_MM_UNPROCESSED = ModuleInfo.FLAG_CUSTOM_INTERNAL
        private const val FLAGS_KEEP_INIT =
            FLAG_MM_UNPROCESSED or ModuleInfo.FLAGS_MODULE_ACTIVE or ModuleInfo.FLAG_MODULE_UPDATING_ONLY
        private const val FLAGS_RESET_UPDATE = FLAG_MM_INVALID or FLAG_MM_UNPROCESSED
        private var instAnce: ModuleManager? = null
        @JvmStatic
        val instance: ModuleManager?
            get() {
                if (instAnce == null) {
                    instAnce = ModuleManager()
                }
                return instAnce
            }
        private const val FLAG_MM_REMOTE_MODULE = ModuleInfo.FLAG_MM_REMOTE_MODULE
        fun isModuleActive(moduleId: String): Boolean {
            val moduleInfo: ModuleInfo? = instance!!.modules[moduleId]
            return moduleInfo != null && moduleInfo.flags and ModuleInfo.FLAGS_MODULE_ACTIVE != 0
        }
    }
}