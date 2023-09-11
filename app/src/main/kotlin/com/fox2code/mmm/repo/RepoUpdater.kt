/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.repo

import androidx.room.Room
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.room.ModuleListCache
import com.fox2code.mmm.utils.room.ModuleListCacheDatabase
import com.fox2code.mmm.utils.room.ReposListDatabase
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class RepoUpdater(repoData2: RepoData) {
    private var indexRaw: ByteArray? = null

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
        // if MainApplication.repoModules is not empty, return it
        /*if (MainApplication.INSTANCE!!.repoModules.isNotEmpty()) {
            if (BuildConfig.DEBUG) Timber.d("Returning MainApplication.repoModules for %s", repoData.preferenceId)
            // convert to list for toUpdate
            val toUpdateList = ArrayList<RepoModule>()
            for (module in MainApplication.INSTANCE!!.repoModules) {
                toUpdateList.add(module.value)
            }
            toUpdate = toUpdateList
            // toapply is a collection of RepoModule, so we need to convert the list to a set
            toApply = HashSet(MainApplication.INSTANCE!!.repoModules.values)
            return toUpdate!!.size
        }*/
        // if we shouldn't update, get the values from the ModuleListCache realm
        if (!repoData.shouldUpdate() && repoData.preferenceId == "androidacy_repo") { // for now, only enable cache reading for androidacy repo, until we handle storing module prop file values in cache
            if (BuildConfig.DEBUG) Timber.d("Fetching index from cache for %s", repoData.preferenceId)
            // now the above but for room
            val db = Room.databaseBuilder(
                MainApplication.INSTANCE!!,
                ModuleListCacheDatabase::class.java,
                "ModuleListCache.db"
            ).allowMainThreadQueries().build()
            val moduleListCacheDao = db.moduleListCacheDao()
            // now we have the cache, we need to check if it's up to date
            var results = moduleListCacheDao.getByRepoId(repoData.preferenceId!!)
            if (results.isNotEmpty()) {
                toUpdate = emptyList()
                toApply = HashSet()
                // if results is not empty, check each module to see if it's null. this should never happen, but if it does, remove it from the cache
                // copy results to a new list so we can remove items from the original list, then set results to the new list
                val resultsCopy = ArrayList(results)
                resultsCopy.removeIf { it == null }
                results = resultsCopy
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
                if (BuildConfig.DEBUG) Timber.d(
                    "Fetched %d modules from cache for %s, from %s records",
                    (toApply as HashSet<RepoModule>).size,
                    repoData.preferenceId,
                    results.size
                )
                val jsonObject = JSONObject()
                // apply the toApply list to the toUpdate list
                try {
                    jsonObject.put("modules", JSONArray(results))
                    toUpdate = repoData.populate(jsonObject)
                } catch (e: Exception) {
                    Timber.e(e)
                }
                // log first 100 chars of indexRaw
                indexRaw = jsonObject.toString().toByteArray()
                if (BuildConfig.DEBUG) Timber.d(
                    "Index raw: %s",
                    String(indexRaw!!, StandardCharsets.UTF_8).subSequence(0, 100)
                )
                // Since we reuse instances this should work
                toApply = HashSet(repoData.moduleHashMap.values)
                (toApply as HashSet<RepoModule>).removeAll(toUpdate!!.toSet())
                // Return repo to update
                return toUpdate!!.size
            }
        }
        return try {
            if (!repoData.prepare()) {
                indexRaw = null
                toUpdate = emptyList()
                toApply = repoData.moduleHashMap.values
                return 0
            }
            indexRaw = doHttpGet(repoData.url, false)
            toUpdate = repoData.populate(JSONObject(String(indexRaw!!, StandardCharsets.UTF_8)))
            // Since we reuse instances this should work
            toApply = HashSet(repoData.moduleHashMap.values)
            // add toApply to the hashmap MainApplication.INSTANCE!!.repoModules
            MainApplication.INSTANCE!!.repoModules.putAll(repoData.moduleHashMap)
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
        // If repo is not enabled we don't need to do anything, just return true
        if (!repoData.isEnabled) {
            if (BuildConfig.DEBUG) Timber.d("Repo %s is disabled, skipping", repoData.preferenceId)
            return true
        }
        val success = AtomicBoolean(false)
        if (BuildConfig.DEBUG) Timber.d("Finishing update for %s", repoData.preferenceId)
        if (indexRaw != null) {
            val tmpIndexRaw = indexRaw!!
            if (BuildConfig.DEBUG) Timber.d("Updating database for %s", repoData.preferenceId)
            // new thread to update the database
            val thread = Thread {
                val startTime = System.currentTimeMillis()
                if (BuildConfig.DEBUG) Timber.d("Updating database thread for %s", repoData.preferenceId)
                try {
                    // iterate over modules, using this.supportedProperties as a template to attempt to get each property from the module. everything that is not null is added to the module
                    // use room to insert to
                    // props avail:
                    val db = Room.databaseBuilder(
                        MainApplication.INSTANCE!!,
                        ModuleListCacheDatabase::class.java,
                        "ModuleListCache.db"
                    ).build()
                    // all except first six can be null
                    // this.indexRaw is the raw index file (json)
                    val modules = JSONObject(String(tmpIndexRaw, StandardCharsets.UTF_8))
                    // androidacy repo uses "data" key, others should use "modules" key. Both are JSONArrays
                    val modulesArray = try {
                        modules.getJSONArray("data")
                    } catch (e: Exception) {
                        modules.getJSONArray("modules")
                    } catch (e: Exception) {
                        Timber.e(e)
                        Timber.w("No modules were found in the index file for %s", repoData.preferenceId)
                        if (BuildConfig.DEBUG) Timber.d("Finished updating database for %s in %dms", repoData.preferenceId, System.currentTimeMillis() - startTime)
                        success.set(false)
                        return@Thread
                    }
                    if (BuildConfig.DEBUG) Timber.d("Got modules for %s", repoData.preferenceId)
                    val moduleListCacheDao = db.moduleListCacheDao()
                    moduleListCacheDao.deleteByRepoId(repoData.preferenceId!!)
                    if (BuildConfig.DEBUG) Timber.d("Deleted old modules for %s", repoData.preferenceId)
                    if (modulesArray.length() == 0) {
                        Timber.w("No modules were found in the index file for %s", repoData.preferenceId)
                        if (BuildConfig.DEBUG) Timber.d("Finished updating database for %s in %dms", repoData.preferenceId, System.currentTimeMillis() - startTime)
                        success.set(false)
                        return@Thread
                    }
                    if (BuildConfig.DEBUG) Timber.d("Iterating over modules for %s", repoData.preferenceId)
                    // iterate over modules
                    for (n in 0 until modulesArray.length()) {
                        // get module
                        val module = modulesArray.getJSONObject(n)
                        try {
                            // get module id
                            // if codename is present, prefer that over id
                            val id: String  =
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
                            val moduleListCache = ModuleListCache(
                                name = name,
                                version = version,
                                versionCode = versionCode,
                                author = author,
                                description = description,
                                minApi = minApiInt,
                                maxApi = maxApiInt,
                                minMagisk = minMagiskInt,
                                needRamdisk = needRamdisk,
                                support = support ?: "",
                                donate = donate ?: "",
                                config = config ?: "",
                                changeBoot = changeBoot,
                                mmtReborn = mmtReborn,
                                repoId = repoId!!,
                                safe = safe,
                                lastUpdate = lastUpdate.toLong(),
                                stats = downloads,
                                codename = id
                            )
                            moduleListCacheDao.insert(moduleListCache)
                        } catch (ignored: Exception) {
                        }
                    }
                    db.close()
                    val endTime = System.currentTimeMillis()
                    val timeTaken = endTime - startTime
                    if (BuildConfig.DEBUG) Timber.d("Time taken to parse modules: $timeTaken ms")
                } catch (ignored: Exception) {
                }
            }
            thread.start()
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
            success.set(true)
        } else {
            if (BuildConfig.DEBUG) Timber.d("No index file found for %s", repoData.preferenceId)
            success.set(true) // assume we're reading from cache. this may be unsafe but it's better than nothing
        }
        return success.get()
    }
}
