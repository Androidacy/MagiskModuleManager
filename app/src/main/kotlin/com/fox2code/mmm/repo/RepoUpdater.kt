/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.repo

import androidx.room.Room
import com.fox2code.mmm.BuildConfig
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
                Timber.d("Got %d modules from cache for %s", results.size, repoData.preferenceId)
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
                val jsonArray = JSONArray()
                for (module in results) {
                    val moduleJson = JSONObject()
                    moduleJson.put("id", module!!.codename)
                    moduleJson.put("name", module.name)
                    moduleJson.put("description", module.description)
                    moduleJson.put("author", module.author)
                    moduleJson.put("donate", module.donate)
                    moduleJson.put("config", module.config)
                    moduleJson.put("support", module.support)
                    moduleJson.put("version", module.version)
                    moduleJson.put("versionCode", module.versionCode)
                    moduleJson.put("minApi", module.minApi)
                    moduleJson.put("maxApi", module.maxApi)
                    moduleJson.put("minMagisk", module.minMagisk)
                    jsonArray.put(moduleJson)
                }
                // apply the toApply list to the toUpdate list
                try {
                    jsonObject.put("modules", jsonArray)
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
                if (BuildConfig.DEBUG) Timber.d("Returning %d modules for %s", toUpdate!!.size, repoData.preferenceId)
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
