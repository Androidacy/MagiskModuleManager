/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.repo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.Companion.getSharedPreferences
import com.fox2code.mmm.MainApplication.Companion.isAndroidacyTestMode
import com.fox2code.mmm.MainApplication.Companion.isDisableLowQualityModuleFilter
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks.Companion.onRepoManagerInitialize
import com.fox2code.mmm.XHooks.Companion.onRepoManagerInitialized
import com.fox2code.mmm.XRepo
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.androidacy.AndroidacyRepoData.Companion.instance
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.utils.SyncManager
import com.fox2code.mmm.utils.io.Files.Companion.write
import com.fox2code.mmm.utils.io.Hashes.Companion.hashSha256
import com.fox2code.mmm.utils.io.PropUtils.Companion.isLowQualityModule
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.io.net.Http.Companion.hasConnectivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets

@Suppress("NAME_SHADOWING")
class RepoManager private constructor(mainApplication: MainApplication) : SyncManager() {
    private val mainApplication: MainApplication
    private val repoData: LinkedHashMap<String?, RepoData>
    val modules: HashMap<String, RepoModule>
        get() {
            afterUpdate()
            return field
        }
    private var repoLastErrorName: String? = null
    var androidacyRepoData: AndroidacyRepoData? = null
    var customRepoManager: CustomRepoManager? = null
    private var initialized: Boolean
    var isLastUpdateSuccess = false
        private set

    init {
        INSTANCE = this // Set early fox XHooks
        initialized = false
        this.mainApplication = mainApplication
        repoData = LinkedHashMap()
        modules = HashMap()
        // refuse to load if setup is not complete
        if (getSharedPreferences("mmm")!!.getString("last_shown_setup", "") == "v4") {
            // We do not have repo list config yet.
            androidacyRepoData = addAndroidacyRepoData()
            val altRepo = addRepoData(MAGISK_ALT_REPO, "Magisk Modules Alt Repo")
            altRepo.defaultWebsite = MAGISK_ALT_REPO_HOMEPAGE
            altRepo.defaultSubmitModule =
                "https://github.com/Magisk-Modules-Alt-Repo/submission/issues"
            customRepoManager = CustomRepoManager(mainApplication, this)
            onRepoManagerInitialize()
            // Populate default cache
            var x = false
            for (repoData in repoData.values) {
                if (repoData === androidacyRepoData) {
                    if (x) {
                        //
                    } else {
                        x = true
                    }
                }
                populateDefaultCache(repoData)
            }
            initialized = true
        }
    }

    private fun populateDefaultCache(repoData: RepoData?) {
        // if last_shown_setup is not "v4", them=n refuse to continue
        if (getSharedPreferences("mmm")!!.getString("last_shown_setup", "") != "v4") {
            return
        }
        // make sure repodata is not null
        if (repoData?.moduleHashMap == null) {
            return
        }
        for (repoModule in repoData.moduleHashMap.values) {
            if (!repoModule.moduleInfo.hasFlag(ModuleInfo.FLAG_METADATA_INVALID)) {
                val registeredRepoModule = modules[repoModule.id]
                if (registeredRepoModule == null) {
                    modules[repoModule.id] = repoModule
                } else if (instance.isEnabled && registeredRepoModule.repoData === androidacyRepoData) {
                    // empty
                } else if (instance.isEnabled && repoModule.repoData === androidacyRepoData) {
                    modules[repoModule.id] = repoModule
                } else if (repoModule.moduleInfo.versionCode > registeredRepoModule.moduleInfo.versionCode) {
                    modules[repoModule.id] = repoModule
                }
            } else {
                Timber.e("Detected module with invalid metadata: " + repoModule.repoName + "/" + repoModule.id)
            }
        }
    }

    operator fun get(url: String?): RepoData? {
        var url = url ?: return null
        if (MAGISK_ALT_REPO_JSDELIVR == url) {
            url = MAGISK_ALT_REPO
        }
        return repoData[url]
    }

    @JvmOverloads
    fun addOrGet(url: String, fallBackName: String? = null): RepoData? {
        var url = url
        if (MAGISK_ALT_REPO_JSDELIVR == url) url = MAGISK_ALT_REPO
        var repoData: RepoData?
        synchronized(syncLock) {
            repoData = this.repoData[url]
            if (repoData == null) {
                return if (ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT == url || ANDROIDACY_MAGISK_REPO_ENDPOINT == url) {
                    androidacyRepoData ?: addAndroidacyRepoData()
                } else {
                    addRepoData(url, fallBackName)
                }
            }
        }
        return repoData
    }

    @SuppressLint("StringFormatInvalid")
    override fun scanInternal(updateListener: UpdateListener) {
        // Refuse to start if first_launch is not false in shared preferences
        if (MainActivity.doSetupNowRunning) {
            return
        }
        modules.clear()
        updateListener.update(0)
        // Using LinkedHashSet to deduplicate Androidacy entry.
        val repoDatas = LinkedHashSet(repoData.values).toTypedArray()
        val repoUpdaters = arrayOfNulls<RepoUpdater>(repoDatas.size)
        var moduleToUpdate = 0
        if (!this.hasConnectivity()) {
            updateListener.update(STEP3)
            return
        }
        for (i in repoDatas.indices) {
            updateListener.update(STEP1 * (i / repoDatas.size))
            if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Preparing to fetch: %s", repoDatas[i].name)
            moduleToUpdate += RepoUpdater(repoDatas[i]).also { repoUpdaters[i] = it }.fetchIndex()
            // divvy the 40 of step1 to each repo
            updateListener.update(STEP1 * ((i + 1) / repoDatas.size))
        }
        if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Updating meta-data")
        var updatedModules = 0
        val allowLowQualityModules = isDisableLowQualityModuleFilter
        for (i in repoUpdaters.indices) {
            // Check if the repo is enabled
            if (!repoUpdaters[i]!!.repoData.isEnabled) {
                if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d(
                    "Skipping disabled repo: %s",
                    repoUpdaters[i]!!.repoData.name
                )
                continue
            }
            val repoModules = repoUpdaters[i]!!.toUpdate()
            val repoData = repoDatas[i]
            if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Registering %s", repoData.name)
            for (repoModule in repoModules!!) {
                try {
                    if (repoModule.propUrl != null && repoModule.propUrl!!.isNotEmpty()) {
                        repoData.storeMetadata(
                            repoModule, doHttpGet(
                                repoModule.propUrl!!, false
                            )
                        )
                        write(
                            File(repoData.cacheRoot, repoModule.id + ".prop"), doHttpGet(
                                repoModule.propUrl!!, false
                            )
                        )
                    }
                    if (repoData.tryLoadMetadata(repoModule) && (allowLowQualityModules || !isLowQualityModule(
                            repoModule.moduleInfo
                        ))
                    ) {
                        // Note: registeredRepoModule may not be null if registered by multiple repos
                        val registeredRepoModule = modules[repoModule.id]
                        if (registeredRepoModule == null) {
                            modules[repoModule.id] = repoModule
                        } else if (instance.isEnabled && registeredRepoModule.repoData === androidacyRepoData) {
                            // empty
                        } else if (instance.isEnabled && repoModule.repoData === androidacyRepoData) {
                            modules[repoModule.id] = repoModule
                        } else if (repoModule.moduleInfo.versionCode > registeredRepoModule.moduleInfo.versionCode) {
                            modules[repoModule.id] = repoModule
                        }
                    } else {
                        repoModule.moduleInfo.flags =
                            repoModule.moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
                    }
                } catch (e: Exception) {
                    Timber.e(e)
                }
                updatedModules++
                val repoProgressIncrement = STEP2 / repoDatas.size.toDouble()
                val moduleProgressIncrement = repoProgressIncrement / repoModules.size.toDouble()
                updateListener.update((STEP1 + moduleProgressIncrement * updatedModules).toInt())
            }
            for (repoModule in repoUpdaters[i]!!.toApply()!!) {
                if (repoModule.moduleInfo.flags and ModuleInfo.FLAG_METADATA_INVALID == 0) {
                    val registeredRepoModule = modules[repoModule.id]
                    if (registeredRepoModule == null) {
                        modules[repoModule.id] = repoModule
                    } else if (instance.isEnabled && registeredRepoModule.repoData === androidacyRepoData) {
                        // empty
                    } else if (instance.isEnabled && repoModule.repoData === androidacyRepoData) {
                        modules[repoModule.id] = repoModule
                    } else if (repoModule.moduleInfo.versionCode > registeredRepoModule.moduleInfo.versionCode) {
                        modules[repoModule.id] = repoModule
                    }
                }
            }
            MainApplication.INSTANCE!!.repoModules.putAll(modules)
        }
        if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Finishing update")
        if (hasConnectivity()) {
            for (i in repoDatas.indices) {
                // If repo is not enabled, skip
                if (!repoDatas[i].isEnabled) {
                    if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Skipping ${repoDatas[i].name} because it's disabled")
                    continue
                }
                if (BuildConfig.DEBUG) if (BuildConfig.DEBUG) Timber.d("Finishing: %s", repoUpdaters[i]!!.repoData.name)
                isLastUpdateSuccess = repoUpdaters[i]!!.finish()
                if (!isLastUpdateSuccess || modules.isEmpty()) {
                    Timber.e("Failed to update %s", repoUpdaters[i]!!.repoData.name)
                    // Show snackbar on main looper and add some bottom padding
                    val context: Activity? = MainApplication.INSTANCE!!.lastActivity
                    Handler(Looper.getMainLooper()).post {
                        if (context != null) {
                            // Show material dialogue with the repo name. for androidacy repo, show an option to reset the api key. show a message then a list of errors
                            val builder = MaterialAlertDialogBuilder(context)
                            builder.setTitle(R.string.repo_update_failed)
                            builder.setMessage(
                                context.getString(
                                    R.string.repo_update_failed_message,
                                    "- " + repoUpdaters[i]!!.repoData.name
                                )
                            )
                            builder.setPositiveButton(android.R.string.ok, null)
                            if (repoUpdaters[i]!!.repoData is AndroidacyRepoData) {
                                builder.setNeutralButton(R.string.reset_api_key) { _: DialogInterface?, _: Int ->
                                    val editor = getSharedPreferences("androidacy")!!
                                        .edit()
                                    editor.putString("androidacy_api_key", "")
                                    editor.apply()
                                    Toast.makeText(
                                        context,
                                        R.string.api_key_removed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    // refresh by faking user pull down
                                    if (MainActivity.INSTANCE != null) {
                                        MainActivity.INSTANCE!!.onRefresh()
                                    }
                                }
                            }
                            builder.show()
                        }
                    }
                    repoLastErrorName = repoUpdaters[i]!!.repoData.name
                }
                updateListener.update(STEP1 + (STEP2 * (i / repoUpdaters.size)))
            }
        }
        Timber.i("Got " + modules.size + " modules!")
        updateListener.update(STEP1 + STEP2 + STEP3)
    }

    fun updateEnabledStates() {
        for (repoData in repoData.values) {
            val wasEnabled = repoData.isEnabled
            repoData.updateEnabledState()
            if (!wasEnabled && repoData.isEnabled) {
                customRepoManager!!.dirty = true
            }
        }
    }

    fun hasConnectivity(): Boolean {
        return hasConnectivity(MainApplication.INSTANCE!!.applicationContext)
    }

    private fun addRepoData(url: String, fallBackName: String?): RepoData {
        val id = internalIdOfUrl(url)
        val cacheRoot = File(mainApplication.dataDir, "repos/$id")
        val repoData =
            if (id.startsWith("repo_")) CustomRepoData(url, cacheRoot) else RepoData(url, cacheRoot)
        if (!fallBackName.isNullOrEmpty()) {
            repoData.defaultName = fallBackName
            if (repoData is CustomRepoData) {
                repoData.loadedExternal = true
                customRepoManager!!.dirty = true
                repoData.updateEnabledState()
            }
        }
        when (url) {
            MAGISK_REPO, MAGISK_REPO_MANAGER -> repoData.defaultWebsite = MAGISK_REPO_HOMEPAGE
        }
        this.repoData[url] = repoData
        if (initialized) {
            populateDefaultCache(repoData)
        }
        return repoData
    }

    private fun addAndroidacyRepoData(): AndroidacyRepoData {
        // cache dir is actually under app data
        val cacheRoot = mainApplication.getDataDirWithPath("repos/androidacy_repo")
        val repoData = AndroidacyRepoData(cacheRoot, isAndroidacyTestMode)
        this.repoData[ANDROIDACY_MAGISK_REPO_ENDPOINT] =
            repoData
        this.repoData[ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT] = repoData
        return repoData
    }

    val xRepos: Collection<XRepo>
        get() = LinkedHashSet<XRepo>(repoData.values)

    companion object {
        const val MAGISK_REPO =
            "https://raw.githubusercontent.com/Magisk-Modules-Repo/submission/modules/modules.json"
        const val MAGISK_REPO_HOMEPAGE = "https://github.com/Magisk-Modules-Repo"
        const val MAGISK_ALT_REPO =
            "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json"
        const val MAGISK_ALT_REPO_HOMEPAGE = "https://github.com/Magisk-Modules-Alt-Repo"
        const val MAGISK_ALT_REPO_JSDELIVR =
            "https://cdn.jsdelivr.net/gh/Magisk-Modules-Alt-Repo/json@main/modules.json"
        const val ANDROIDACY_MAGISK_REPO_ENDPOINT =
            "https://production-api.androidacy.com/magisk/repo"
        const val ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT =
            "https://staging-api.androidacy.com/magisk/repo"
        const val ANDROIDACY_MAGISK_REPO_HOMEPAGE = "https://www.androidacy.com/modules-repo"
        private const val MAGISK_REPO_MANAGER =
            "https://magisk-modules-repo.github.io/submission/modules.json"
        private val lock = Any()
        private const val STEP1 = 20
        private const val STEP2 = 60
        private const val STEP3 = 20

        @Volatile
        private var INSTANCE: RepoManager? = null

        fun getINSTANCE(): RepoManager? {
            if (INSTANCE == null || !INSTANCE!!.initialized) {
                synchronized(lock) {
                    if (INSTANCE == null) {
                        val mainApplication = MainApplication.INSTANCE
                        if (mainApplication != null) {
                            INSTANCE = RepoManager(mainApplication)
                            onRepoManagerInitialized()
                        } else {
                            throw RuntimeException("Getting RepoManager too soon!")
                        }
                    }
                }
            }
            return INSTANCE
        }

        val iNSTANCE_UNSAFE: RepoManager?
            get() {
                if (INSTANCE == null) {
                    synchronized(lock) {
                        if (INSTANCE == null) {
                            val mainApplication = MainApplication.INSTANCE
                            if (mainApplication != null) {
                                INSTANCE = RepoManager(mainApplication)
                                onRepoManagerInitialized()
                            } else {
                                throw RuntimeException("Getting RepoManager too soon!")
                            }
                        }
                    }
                }
                return INSTANCE
            }

        fun internalIdOfUrl(url: String): String {
            return when (url) {
                MAGISK_ALT_REPO, MAGISK_ALT_REPO_JSDELIVR -> "magisk_alt_repo"
                ANDROIDACY_MAGISK_REPO_ENDPOINT, ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT -> "androidacy_repo"
                else -> "repo_" + hashSha256(url.toByteArray(StandardCharsets.UTF_8))
            }
        }

        fun isBuiltInRepo(repo: String?): Boolean {
            return when (repo) {
                ANDROIDACY_MAGISK_REPO_ENDPOINT, ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT, MAGISK_ALT_REPO, MAGISK_ALT_REPO_JSDELIVR -> true
                else -> false
            }
        }

        /**
         * Safe way to do `RepoManager.getINSTANCE()!!.androidacyRepoData.isEnabled()`
         * without initializing RepoManager
         */
        val isAndroidacyRepoEnabled: Boolean
            get() = INSTANCE != null && INSTANCE!!.androidacyRepoData != null && INSTANCE!!.androidacyRepoData!!.isEnabled
    }
}
