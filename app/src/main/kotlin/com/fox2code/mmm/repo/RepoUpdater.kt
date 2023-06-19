/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.repo

import androidx.room.Room
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.room.ModuleListCacheDatabase
import com.fox2code.mmm.utils.room.ReposListDatabase
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class RepoUpdater(repoData2: RepoData) {
    private var indexRaw: ByteArray? = null

    @JvmField
    var repoData: RepoData = repoData2
    private var toUpdate: List<RepoModule>? = null
    private var toApply: Collection<RepoModule>? = null
    fun fetchIndex(): Int {
        if (!RepoManager.getINSTANCE()!!.hasConnectivity()) {
            indexRaw = null
            toUpdate = emptyList()
            toApply = emptySet()
            return 0
        }
        if (!repoData.isEnabled) {
            indexRaw = null
            toUpdate = emptyList()
            toApply = emptySet()
            return 0
        }
        // if we shouldn't update, get the values from the ModuleListCache realm
        if (!repoData.shouldUpdate() && repoData.preferenceId == "androidacy_repo") { // for now, only enable cache reading for androidacy repo, until we handle storing module prop file values in cache
            Timber.d("Fetching index from cache for %s", repoData.preferenceId)
            // now the above but for room
            val db = Room.databaseBuilder(
                MainApplication.INSTANCE!!,
                ModuleListCacheDatabase::class.java,
                "ModuleListCache.db"
            ).allowMainThreadQueries().build()
            val moduleListCacheDao = db.moduleListCacheDao()
            // now we have the cache, we need to check if it's up to date
            val results = moduleListCacheDao.getByRepoId(repoData.preferenceId!!)
            toUpdate = emptyList()
            toApply = HashSet()
            for (moduleListCache in results) {
                (toApply as HashSet<RepoModule>).add(
                    RepoModule(
                        repoData,
                        moduleListCache.codename,
                        moduleListCache.name,
                        moduleListCache.description,
                        moduleListCache.author,
                        moduleListCache.donate,
                        moduleListCache.config,
                        moduleListCache.support,
                        moduleListCache.version,
                        moduleListCache.versionCode
                    )
                )
            }
            Timber.d(
                "Fetched %d modules from cache for %s, from %s records",
                (toApply as HashSet<RepoModule>).size,
                repoData.preferenceId,
                results.size
            )
            // apply the toApply list to the toUpdate list
            try {
                val jsonObject = JSONObject()
                jsonObject.put("modules", JSONArray(results))
                toUpdate = repoData.populate(jsonObject)
            } catch (e: Exception) {
                Timber.e(e)
            }
            // Since we reuse instances this should work
            toApply = HashSet(repoData.moduleHashMap.values)
            (toApply as HashSet<RepoModule>).removeAll(toUpdate!!.toSet())
            // Return repo to update
            return toUpdate!!.size
        }
        return try {
            if (!repoData.prepare()) {
                indexRaw = null
                toUpdate = emptyList()
                toApply = repoData.moduleHashMap.values
                return 0
            }
            indexRaw = repoData.getUrl()?.let { doHttpGet(it, false) }
            toUpdate = repoData.populate(JSONObject(String(indexRaw!!, StandardCharsets.UTF_8)))
            // Since we reuse instances this should work
            toApply = HashSet(repoData.moduleHashMap.values)
            (toUpdate as MutableList<RepoModule>?)?.let {
                (toApply as HashSet<RepoModule>).removeAll(
                    it.toSet()
                )
            }
            // Return repo to update
            (toUpdate as MutableList<RepoModule>?)!!.size
        } catch (e: Exception) {
            Timber.e(e)
            indexRaw = null
            toUpdate = emptyList()
            toApply = emptySet()
            0
        }
    }

    fun toUpdate(): List<RepoModule>? {
        return toUpdate
    }

    fun toApply(): Collection<RepoModule>? {
        return toApply
    }

    fun finish(): Boolean {
        val success = AtomicBoolean(false)
        // If repo is not enabled we don't need to do anything, just return true
        if (!repoData.isEnabled) {
            return true
        }
        if (indexRaw != null) {
            try {
                // iterate over modules, using this.supportedProperties as a template to attempt to get each property from the module. everything that is not null is added to the module
                // use room to insert to
                // props avail:
                val db = Room.databaseBuilder(
                    MainApplication.INSTANCE!!,
                    ModuleListCacheDatabase::class.java,
                    "ModuleListCache.db"
                ).allowMainThreadQueries().build()
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
                //
                // all except first six can be null
                // this.indexRaw is the raw index file (json)
                val modules = JSONObject(String(indexRaw!!, StandardCharsets.UTF_8))
                // androidacy repo uses "data" key, others should use "modules" key. Both are JSONArrays
                val modulesArray: JSONArray = if (repoData.name == "Androidacy Modules Repo") {
                    // get modules from "data" key. This is a JSONArray so we need to convert it to a JSONObject
                    modules.getJSONArray("data")
                } else {
                    // get modules from "modules" key. This is a JSONArray so we need to convert it to a JSONObject
                    modules.getJSONArray("modules")
                }
                val moduleListCacheDao = db.moduleListCacheDao()
                moduleListCacheDao.deleteByRepoId(repoData.preferenceId!!)
                // iterate over modules. pls don't hate me for this, its ugly but it works
                for (n in 0 until modulesArray.length()) {
                    // get module
                    val module = modulesArray.getJSONObject(n)
                    try {
                        // get module id
                        // if codename is present, prefer that over id
                        val id: String? =
                            if (module.has("codename") && module.getString("codename") != "") {
                                module.getString("codename")
                            } else {
                                module.getString("id")
                            }
                        // get module name
                        val name = module.getString("name")
                        // get module version
                        val version = module.getString("version")
                        // get module version code
                        val versionCode = module.getInt("versionCode")
                        // get module author
                        val author = module.getString("author")
                        // get module description
                        val description = module.getString("description")
                        // get module min api
                        val minApi: String =
                            if (module.has("minApi") && module.getString("minApi") != "") {
                                module.getString("minApi")
                            } else {
                                "0"
                            }
                        // coerce min api to int
                        val minApiInt = minApi.toInt()
                        // get module max api and set to 0 if it's "" or null
                        val maxApi: String =
                            if (module.has("maxApi") && module.getString("maxApi") != "") {
                                module.getString("maxApi")
                            } else {
                                "0"
                            }
                        // coerce max api to int
                        val maxApiInt = maxApi.toInt()
                        // get module min magisk
                        val minMagisk: String =
                            if (module.has("minMagisk") && module.getString("minMagisk") != "") {
                                module.getString("minMagisk")
                            } else {
                                "0"
                            }
                        // coerce min magisk to int
                        val minMagiskInt = minMagisk.toInt()
                        // get module need ramdisk
                        val needRamdisk: Boolean = if (module.has("needRamdisk")) {
                            module.getBoolean("needRamdisk")
                        } else {
                            false
                        }
                        // get module support
                        val support: String? = if (module.has("support")) {
                            module.getString("support")
                        } else {
                            ""
                        }
                        // get module donate
                        val donate: String? = if (module.has("donate")) {
                            module.getString("donate")
                        } else {
                            ""
                        }
                        // get module config
                        val config: String? = if (module.has("config")) {
                            module.getString("config")
                        } else {
                            ""
                        }
                        // get module change boot
                        val changeBoot: Boolean = if (module.has("changeBoot")) {
                            module.getBoolean("changeBoot")
                        } else {
                            false
                        }
                        // get module mmt reborn
                        val mmtReborn: Boolean = if (module.has("mmtReborn")) {
                            module.getBoolean("mmtReborn")
                        } else {
                            false
                        }
                        // try to get updated_at or lastUpdate value for lastUpdate
                        val lastUpdate: Int = if (module.has("updated_at")) {
                            module.getInt("updated_at")
                        } else if (module.has("lastUpdate")) {
                            module.getInt("lastUpdate")
                        } else {
                            0
                        }
                        // now downloads or stars
                        val downloads: Int = if (module.has("downloads")) {
                            module.getInt("downloads")
                        } else if (module.has("stars")) {
                            module.getInt("stars")
                        } else {
                            0
                        }
                        // get module repo id
                        val repoId = repoData.preferenceId
                        // get safe property. for now, only supported by androidacy repo and they use "vt_status" key
                        var safe = false
                        if (repoData.name == "Androidacy Modules Repo") {
                            if (module.has("vt_status")) {
                                if (module.getString("vt_status") == "Clean") {
                                    safe = true
                                }
                            }
                        }
                        moduleListCacheDao.insert(name = name, version = version, versionCode = versionCode, author = author, description = description, minApi = minApiInt, maxApi = maxApiInt, minMagisk = minMagiskInt, needRamdisk = needRamdisk, support = support ?: "", donate = donate ?: "", config = config ?: "", changeBoot = changeBoot, mmtReborn = mmtReborn, repoId = repoId!!, safe = safe, lastUpdate = lastUpdate.toLong(), stats = downloads, codename = id ?: "")
                    } catch (ignored: Exception) {
                    }
                }
            } catch (ignored: Exception) {
            }
            indexRaw = null
            // set lastUpdate
            val db = Room.databaseBuilder(
                MainApplication.INSTANCE!!.applicationContext,
               ReposListDatabase::class.java,
                "ReposList.db"
            ).allowMainThreadQueries().build()
            val repoListDao = db.reposListDao()
            repoListDao.setLastUpdate(repoData.preferenceId!!, System.currentTimeMillis())
            db.close()
        } else {
            success.set(true) // assume we're reading from cache. this may be unsafe but it's better than nothing
        }
        return success.get()
    }
}
