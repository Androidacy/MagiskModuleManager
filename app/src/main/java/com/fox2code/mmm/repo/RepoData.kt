/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.repo

import android.net.Uri
import com.fox2code.mmm.AppUpdateManager.Companion.shouldForceHide
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.R
import com.fox2code.mmm.XRepo
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.utils.io.Files.Companion.write
import com.fox2code.mmm.utils.io.PropUtils.Companion.readProperties
import com.fox2code.mmm.utils.realm.ModuleListCache
import com.fox2code.mmm.utils.realm.ReposList
import io.realm.Realm
import io.realm.RealmConfiguration
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LeakingThis", "SENSELESS_COMPARISON", "RedundantSetter")
open class RepoData(url: String, cacheRoot: File) : XRepo() {
    private val supportedProperties = JSONObject()
    private val populateLock = Any()

    @JvmField
    var url: String

    @JvmField
    var preferenceId: String? = null

    @JvmField
    var cacheRoot: File

    @JvmField
    var moduleHashMap: HashMap<String, RepoModule>
    private var metaDataCache: JSONObject?

    @JvmField
    var lastUpdate
            : Long = 0

    @JvmField
    var website: String? = null

    @JvmField
    var support: String? = null

    @JvmField
    var donate: String? = null

    @JvmField
    var submitModule: String? = null

    @JvmField
    var defaultName: String

    @JvmField
    var defaultWebsite: String

    @JvmField
    protected var defaultSupport: String? = null

    @JvmField
    protected var defaultDonate: String? = null

    @JvmField
    var defaultSubmitModule: String? = null

    override var name: String? = null
        get() = {
            // if name is null return defaultName and if defaultName is null return url
            if (field == null) {
                if (defaultName == null) {
                    url
                } else {
                    defaultName
                }
            } else {
                field
            }
        }.toString()
        set(value) {
            field = value
        }

    // array with module info default values
    // supported properties for a module
    //id=<string>
    //name=<string>
    //version=<string>
    //versionCode=<int>
    //author=<string>
    //description=<string>
    //minApi=<int>
    //maxApi=<int>
    //minMagisk=<int>
    //needRamdisk=<boolean>
    //support=<url>
    //donate=<url>
    //config=<package>
    //changeBoot=<boolean>
    //mmtReborn=<boolean>
    // extra properties only useful for the database
    //repoId=<string>
    //installed=<boolean>
    //installedVersionCode=<int> (only if installed)
    var isForceHide: Boolean
        private set
    private var enabled // Cache for speed
            : Boolean

    init {
        // setup supportedProperties
        try {
            supportedProperties.put("id", "")
            supportedProperties.put("name", "")
            supportedProperties.put("version", "")
            supportedProperties.put("versionCode", "")
            supportedProperties.put("author", "")
            supportedProperties.put("description", "")
            supportedProperties.put("minApi", "")
            supportedProperties.put("maxApi", "")
            supportedProperties.put("minMagisk", "")
            supportedProperties.put("needRamdisk", "")
            supportedProperties.put("support", "")
            supportedProperties.put("donate", "")
            supportedProperties.put("config", "")
            supportedProperties.put("changeBoot", "")
            supportedProperties.put("mmtReborn", "")
            supportedProperties.put("repoId", "")
            supportedProperties.put("installed", "")
            supportedProperties.put("installedVersionCode", "")
            supportedProperties.put("safe", "")
        } catch (e: JSONException) {
            Timber.e(e, "Error while setting up supportedProperties")
        }
        this.url = url
        preferenceId = RepoManager.internalIdOfUrl(url)
        this.cacheRoot = cacheRoot
        // metadata cache is a realm database from ModuleListCache
        metaDataCache = null
        moduleHashMap = HashMap()
        defaultName = url // Set url as default name
        val tempVarForPreferenceId = preferenceId!!
        isForceHide = shouldForceHide(tempVarForPreferenceId)
        // this.enable is set from the database
        val realmConfiguration = RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(
            INSTANCE!!.key
        ).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(
            INSTANCE!!.getDataDirWithPath("realms")
        ).schemaVersion(1).build()
        val realm = Realm.getInstance(realmConfiguration)
        val reposList = realm.where(ReposList::class.java).equalTo("id", preferenceId).findFirst()
        if (reposList == null) {
            Timber.d("RepoData for %s not found in database", preferenceId)
            // log every repo in db
            val fullList: Array<Any> = realm.where(ReposList::class.java).findAll().toTypedArray()
            Timber.d("RepoData: " + preferenceId + ". repos in database: " + fullList.size)
            for (repo in fullList) {
                val r = repo as ReposList
                Timber.d("RepoData: " + preferenceId + ". repo: " + r.id + " " + r.name + " " + r.website + " " + r.support + " " + r.donate + " " + r.submitModule + " " + r.isEnabled)
            }
        } else {
            Timber.d("RepoData for %s found in database", preferenceId)
        }
        Timber.d(
            "RepoData: $preferenceId. record in database: " + (reposList?.toString()
                ?: "none")
        )
        enabled = !isForceHide && reposList != null && reposList.isEnabled
        defaultWebsite = "https://" + Uri.parse(url).host + "/"
        // open realm database
        // load metadata from realm database
        if (enabled) {
            try {
                metaDataCache = ModuleListCache.getRepoModulesAsJson(preferenceId)
                // log count of modules in the database
                val tempMetaDataCacheVar = metaDataCache!!
                Timber.d("RepoData: $preferenceId. modules in database: ${tempMetaDataCacheVar.length()}")
                // load repo metadata from ReposList unless it's a built-in repo
                if (RepoManager.isBuiltInRepo(preferenceId)) {
                    name = defaultName
                    website = defaultWebsite
                    support = defaultSupport
                    donate = defaultDonate
                    submitModule = defaultSubmitModule
                } else {
                    // get everything from ReposList realm database
                    name = realm.where(
                        ReposList::class.java
                    ).equalTo("id", preferenceId).findFirst()?.name
                    website = realm.where(
                        ReposList::class.java
                    ).equalTo("id", preferenceId).findFirst()?.website
                    support = realm.where(
                        ReposList::class.java
                    ).equalTo("id", preferenceId).findFirst()?.support
                    donate = realm.where(
                        ReposList::class.java
                    ).equalTo("id", preferenceId).findFirst()?.donate
                    submitModule = realm.where(
                        ReposList::class.java
                    ).equalTo("id", preferenceId).findFirst()?.submitModule
                }
            } catch (e: Exception) {
                Timber.w("Failed to load repo metadata from database: " + e.message + ". If this is a first time run, this is normal.")
            }
        }
        realm.close()
    }

    open fun prepare(): Boolean {
        return true
    }

    @Throws(JSONException::class)
    open fun populate(jsonObject: JSONObject): List<RepoModule>? {
        val newModules: MutableList<RepoModule> = ArrayList()
        synchronized(populateLock) {
            val name = jsonObject.getString("name").trim { it <= ' ' }
            // if Official is present, remove it, or (Official), or [Official]. We don't want to show it in the UI
            var nameForModules =
                if (name.endsWith(" (Official)")) name.substring(0, name.length - 11) else name
            nameForModules = if (nameForModules.endsWith(" [Official]")) nameForModules.substring(
                0,
                nameForModules.length - 11
            ) else nameForModules
            nameForModules =
                if (nameForModules.contains("Official")) nameForModules.replace("Official", "")
                    .trim { it <= ' ' } else nameForModules
            val lastUpdate = jsonObject.getLong("last_update")
            for (repoModule in moduleHashMap.values) {
                repoModule.processed = false
            }
            val array = jsonObject.getJSONArray("modules")
            val len = array.length()
            for (i in 0 until len) {
                val module = array.getJSONObject(i)
                val moduleId = module.getString("id")
                // module IDs must match the regex ^[a-zA-Z][a-zA-Z0-9._-]+$ and cannot be empty or null or equal ak3-helper
                if (moduleId.isEmpty() || moduleId == "ak3-helper" || !moduleId.matches(Regex("^[a-zA-Z][a-zA-Z0-9._-]+$"))) {
                    continue
                }
                // If module id start with a dot, warn user
                if (moduleId[0] == '.') {
                    Timber.w("This is not recommended and may indicate an attempt to hide the module")
                }
                val moduleLastUpdate = module.getLong("last_update")
                val moduleNotesUrl = module.getString("notes_url")
                val modulePropsUrl = module.getString("prop_url")
                val moduleZipUrl = module.getString("zip_url")
                val moduleChecksum = module.optString("checksum")
                val moduleStars = module.optString("stars")
                var moduleDownloads = module.optString("downloads")
                // if downloads is mull or empty, try to get it from the stats field
                if (moduleDownloads.isEmpty() && module.has("stats")) {
                    moduleDownloads = module.optString("stats")
                }
                var repoModule = moduleHashMap[moduleId]
                if (repoModule == null) {
                    repoModule = RepoModule(this, moduleId)
                    moduleHashMap[moduleId] = repoModule
                    newModules.add(repoModule)
                } else {
                    if (repoModule.lastUpdated < moduleLastUpdate || repoModule.moduleInfo.hasFlag(
                            ModuleInfo.FLAG_METADATA_INVALID
                        )
                    ) {
                        newModules.add(repoModule)
                    }
                }
                repoModule.processed = true
                repoModule.repoName = nameForModules
                repoModule.lastUpdated = moduleLastUpdate
                repoModule.notesUrl = moduleNotesUrl
                repoModule.propUrl = modulePropsUrl
                repoModule.zipUrl = moduleZipUrl
                repoModule.checksum = moduleChecksum
                // safety check must be overridden per repo. only androidacy repo has this flag currently
                // repoModule.safe = module.optBoolean("safe", false);
                if (moduleStars.isNotEmpty()) {
                    try {
                        repoModule.qualityValue = moduleStars.toInt()
                        repoModule.qualityText = R.string.module_stars
                    } catch (ignored: NumberFormatException) {
                    }
                } else if (moduleDownloads.isNotEmpty()) {
                    try {
                        repoModule.qualityValue = moduleDownloads.toInt()
                        repoModule.qualityText = R.string.module_downloads
                    } catch (ignored: NumberFormatException) {
                    }
                }
            }
            // Remove no longer existing modules
            val moduleInfoIterator = moduleHashMap.values.iterator()
            while (moduleInfoIterator.hasNext()) {
                val repoModule = moduleInfoIterator.next()
                if (!repoModule.processed) {
                    val delete = File(cacheRoot, repoModule.id + ".prop").delete()
                    if (!delete) {
                        throw RuntimeException("Failed to delete module metadata")
                    }
                    moduleInfoIterator.remove()
                } else {
                    repoModule.moduleInfo.verify()
                }
            }
            // Update final metadata
            this.name = name
            this.lastUpdate = lastUpdate
            website = jsonObject.optString("website")
            support = jsonObject.optString("support")
            donate = jsonObject.optString("donate")
            submitModule = jsonObject.optString("submitModule")
        }
        return newModules
    }

    override val isEnabledByDefault: Boolean
        get() = BuildConfig.ENABLED_REPOS.contains(preferenceId)
    override var isEnabled: Boolean = false
        get() = if (field) {
            field
        } else {
            val realmConfiguration2 =
                RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(
                    INSTANCE!!.key
                ).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(
                    INSTANCE!!.getDataDirWithPath("realms")
                ).schemaVersion(1).build()
            val realm2 = Realm.getInstance(realmConfiguration2)
            val dbEnabled = AtomicBoolean(false)
            realm2.executeTransaction { realm: Realm ->
                val reposList =
                    realm.where(ReposList::class.java).equalTo("id", preferenceId).findFirst()
                if (reposList != null) {
                    dbEnabled.set(reposList.isEnabled)
                } else {
                    // should never happen but for safety
                    dbEnabled.set(false)
                }
            }
            realm2.close()
            // should never happen but for safety
            if (dbEnabled.get()) {
                !isForceHide
            } else {
                false
            }
        }
        set(value) {
            field = value
            this.enabled = enabled && !isForceHide
            // reposlist realm
            val realmConfiguration2 =
                RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(
                    INSTANCE!!.key
                ).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(
                    INSTANCE!!.getDataDirWithPath("realms")
                ).schemaVersion(1).build()
            val realm2 = Realm.getInstance(realmConfiguration2)
            realm2.executeTransaction { realm: Realm ->
                val reposList =
                    realm.where(ReposList::class.java).equalTo("id", preferenceId).findFirst()
                if (reposList != null) {
                    reposList.isEnabled = enabled
                }
            }
            realm2.close()
        }

    @Throws(IOException::class)
    open fun storeMetadata(repoModule: RepoModule, data: ByteArray?) {
        write(File(cacheRoot, repoModule.id + ".prop"), data)
    }

    open fun tryLoadMetadata(repoModule: RepoModule): Boolean {
        val file = File(cacheRoot, repoModule.id + ".prop")
        if (file.exists()) {
            try {
                val moduleInfo = repoModule.moduleInfo
                readProperties(
                    moduleInfo,
                    file.absolutePath,
                    repoModule.repoName + "/" + moduleInfo.name,
                    false
                )
                moduleInfo.flags = moduleInfo.flags and ModuleInfo.FLAG_METADATA_INVALID.inv()
                if (moduleInfo.version == null) {
                    moduleInfo.version = "v" + moduleInfo.versionCode
                }
                return true
            } catch (ignored: Exception) {
                val delete = file.delete()
                if (!delete) {
                    throw RuntimeException("Failed to delete invalid metadata file")
                }
            }
        } else {
            Timber.d("Metadata file not found for %s", repoModule.id)
        }
        repoModule.moduleInfo.flags =
            repoModule.moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
        return false
    }

    fun updateEnabledState() {
        // Make sure first_launch preference is set to false
        if (MainActivity.doSetupNowRunning) {
            return
        }
        if (preferenceId == null) {
            Timber.e("Repo ID is null")
            return
        }
        // if repo starts with repo_, it's always enabled bc custom repos can't be disabled without being deleted.
        isForceHide = shouldForceHide(preferenceId!!)
        // reposlist realm
        val realmConfiguration2 =
            RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(
                INSTANCE!!.key
            ).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(
                INSTANCE!!.getDataDirWithPath("realms")
            ).schemaVersion(1).build()
        val realm2 = Realm.getInstance(realmConfiguration2)
        var dbEnabled = false
        try {
            dbEnabled = realm2.where(
                ReposList::class.java
            ).equalTo("id", preferenceId).findFirst()?.isEnabled == true
        } catch (e: Exception) {
            Timber.e(e, "Error while updating enabled state for repo %s", preferenceId)
        }
        realm2.close()
        enabled = !isForceHide && dbEnabled
    }

    open fun getUrl(): String? {
        return url
    }

    fun getWebsite(): String {
        if (isNonNull(website)) return website!!
        return if (defaultWebsite != null) defaultWebsite else url
    }

    fun getSupport(): String? {
        return if (isNonNull(support)) support else defaultSupport
    }

    fun getDonate(): String? {
        return if (isNonNull(donate)) donate else defaultDonate
    }

    fun getSubmitModule(): String? {
        return if (isNonNull(submitModule)) submitModule else defaultSubmitModule
    }

    // should update (lastUpdate > 15 minutes)
    fun shouldUpdate(): Boolean {
        Timber.d("Repo $preferenceId should update check called")
        val realmConfiguration2 =
            RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(
                INSTANCE!!.key
            ).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(
                INSTANCE!!.getDataDirWithPath("realms")
            ).schemaVersion(1).build()
        val realm2 = Realm.getInstance(realmConfiguration2)
        val repo = realm2.where(ReposList::class.java).equalTo("id", preferenceId).findFirst()
        // Make sure ModuleListCache for repoId is not null
        val cacheRoot = INSTANCE!!.getDataDirWithPath("realms/repos/$preferenceId")
        val realmConfiguration =
            RealmConfiguration.Builder().name("ModuleListCache.realm").encryptionKey(
                INSTANCE!!.key
            ).schemaVersion(1).deleteRealmIfMigrationNeeded().allowWritesOnUiThread(true)
                .allowQueriesOnUiThread(true).directory(cacheRoot).build()
        val realm = Realm.getInstance(realmConfiguration)
        val moduleListCache = realm.where(
            ModuleListCache::class.java
        ).equalTo("repoId", preferenceId).findAll()
        if (repo != null) {
            return if (repo.lastUpdate != 0 && moduleListCache.size != 0) {
                val lastUpdate = repo.lastUpdate.toLong()
                val currentTime = System.currentTimeMillis()
                val diff = currentTime - lastUpdate
                val diffMinutes = diff / (60 * 1000) % 60
                Timber.d("Repo $preferenceId updated: $diffMinutes minutes ago")
                realm.close()
                diffMinutes > if (BuildConfig.DEBUG) 15 else 30
            } else {
                Timber.d("Repo $preferenceId should update could not find repo in database")
                Timber.d("This is probably an error, please report this to the developer")
                realm.close()
                true
            }
        } else {
            realm.close()
        }
        return true
    }

    companion object {
        private fun isNonNull(str: String?): Boolean {
            return !str.isNullOrEmpty() && "null" != str
        }
    }
}