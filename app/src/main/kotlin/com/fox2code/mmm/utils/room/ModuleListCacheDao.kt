/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Dao
import androidx.room.Query


// contains
// codename (string, primary), version (string), versionCode (int), author (string), description (string), minApi (int), maxApi (int), minMagisk (int), needRamdisk (boolean), support (string), donate (string), config (string), changeBoot (bool), mmtReborn (bool), repoId (string), lastUpdate (bigint)
@Suppress("unused")
@Dao
interface ModuleListCacheDao {
    // functions:
    // getAll(): List<ModuleListCache>
    // getByRepoId(repoId: String): List<ModuleListCache>
    // getByCodename(codename: String): ModuleListCache
    // insert(moduleListCache: ModuleListCache)
    // update(moduleListCache: ModuleListCache)
    // delete(moduleListCache: ModuleListCache)
    // deleteAll()
    // count(): Int

    // get fun
    // getVersion(codename: String): String
    // getVersionCode(codename: String): Int
    // getAuthor(codename: String): String
    // getDescription(codename: String): String
    // getMinApi(codename: String): Int
    // getMaxApi(codename: String): Int
    // getMinMagisk(codename: String): Int
    // getNeedRamdisk(codename: String): Boolean
    // getSupport(codename: String): String
    // getDonate(codename: String): String
    // getConfig(codename: String): String
    // getChangeBoot(codename: String): Boolean
    // getMmtReborn(codename: String): Boolean
    // getRepoId(codename: String): String
    // getLastUpdate(codename: String): Long

    // set fun
    // setVersion(codename: String, version: String)
    // setVersionCode(codename: String, versionCode: Int)
    // setAuthor(codename: String, author: String)
    // setDescription(codename: String, description: String)
    // setMinApi(codename: String, minApi: Int)
    // setMaxApi(codename: String, maxApi: Int)
    // setMinMagisk(codename: String, minMagisk: Int)
    // setNeedRamdisk(codename: String, needRamdisk: Boolean)
    // setSupport(codename: String, support: String)
    // setDonate(codename: String, donate: String)
    // setConfig(codename: String, config: String)
    // setChangeBoot(codename: String, changeBoot: Boolean)
    // setMmtReborn(codename: String, mmtReborn: Boolean)
    // setRepoId(codename: String, repoId: String)
    // setLastUpdate(codename: String, lastUpdate: Long)

    @Query("SELECT * FROM modulelistcache")
    fun getAll(): List<ModuleListCache>

    @Query("SELECT * FROM modulelistcache WHERE repoId = :repoId")
    fun getByRepoId(repoId: String): List<ModuleListCache>

    @Query("SELECT * FROM modulelistcache WHERE codename = :codename")
    fun getByCodename(codename: String): ModuleListCache

    @Query("INSERT INTO modulelistcache VALUES (:codename, :version, :versionCode, :author, :description, :minApi, :maxApi, :minMagisk, :needRamdisk, :support, :donate, :config, :changeBoot, :mmtReborn, :repoId, :lastUpdate)")
    fun insert(codename: String, version: String, versionCode: Int, author: String, description: String, minApi: Int, maxApi: Int, minMagisk: Int, needRamdisk: Boolean, support: String, donate: String, config: String, changeBoot: Boolean, mmtReborn: Boolean, repoId: String, lastUpdate: Long)

    @Query("UPDATE modulelistcache SET version = :version WHERE codename = :codename")
    fun setVersion(codename: String, version: String)

    @Query("UPDATE modulelistcache SET versionCode = :versionCode WHERE codename = :codename")
    fun setVersionCode(codename: String, versionCode: Int)

    @Query("UPDATE modulelistcache SET author = :author WHERE codename = :codename")
    fun setAuthor(codename: String, author: String)

    @Query("UPDATE modulelistcache SET description = :description WHERE codename = :codename")
    fun setDescription(codename: String, description: String)

    @Query("UPDATE modulelistcache SET minApi = :minApi WHERE codename = :codename")
    fun setMinApi(codename: String, minApi: Int)

    @Query("UPDATE modulelistcache SET maxApi = :maxApi WHERE codename = :codename")
    fun setMaxApi(codename: String, maxApi: Int)

    @Query("UPDATE modulelistcache SET minMagisk = :minMagisk WHERE codename = :codename")
    fun setMinMagisk(codename: String, minMagisk: Int)

    @Query("UPDATE modulelistcache SET needRamdisk = :needRamdisk WHERE codename = :codename")
    fun setNeedRamdisk(codename: String, needRamdisk: Boolean)

    @Query("UPDATE modulelistcache SET support = :support WHERE codename = :codename")
    fun setSupport(codename: String, support: String)

    @Query("UPDATE modulelistcache SET donate = :donate WHERE codename = :codename")
    fun setDonate(codename: String, donate: String)

    @Query("UPDATE modulelistcache SET config = :config WHERE codename = :codename")
    fun setConfig(codename: String, config: String)

    @Query("UPDATE modulelistcache SET changeBoot = :changeBoot WHERE codename = :codename")
    fun setChangeBoot(codename: String, changeBoot: Boolean)

    @Query("UPDATE modulelistcache SET mmtReborn = :mmtReborn WHERE codename = :codename")
    fun setMmtReborn(codename: String, mmtReborn: Boolean)

    @Query("UPDATE modulelistcache SET repoId = :repoId WHERE codename = :codename")
    fun setRepoId(codename: String, repoId: String)

    @Query("UPDATE modulelistcache SET lastUpdate = :lastUpdate WHERE codename = :codename")
    fun setLastUpdate(codename: String, lastUpdate: Long)

    @Query("DELETE FROM modulelistcache WHERE codename = :codename")
    fun delete(codename: String)

    @Query("DELETE FROM modulelistcache")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM modulelistcache")
    fun count(): Int

    @Query("SELECT version FROM modulelistcache WHERE codename = :codename")
    fun getVersion(codename: String): String

    @Query("SELECT versionCode FROM modulelistcache WHERE codename = :codename")
    fun getVersionCode(codename: String): Int

    @Query("SELECT author FROM modulelistcache WHERE codename = :codename")
    fun getAuthor(codename: String): String

    @Query("SELECT description FROM modulelistcache WHERE codename = :codename")
    fun getDescription(codename: String): String

    @Query("SELECT minApi FROM modulelistcache WHERE codename = :codename")
    fun getMinApi(codename: String): Int

    @Query("SELECT maxApi FROM modulelistcache WHERE codename = :codename")
    fun getMaxApi(codename: String): Int

    @Query("SELECT minMagisk FROM modulelistcache WHERE codename = :codename")
    fun getMinMagisk(codename: String): Int

    @Query("SELECT needRamdisk FROM modulelistcache WHERE codename = :codename")
    fun getNeedRamdisk(codename: String): Boolean

    @Query("SELECT support FROM modulelistcache WHERE codename = :codename")
    fun getSupport(codename: String): String

    @Query("SELECT donate FROM modulelistcache WHERE codename = :codename")
    fun getDonate(codename: String): String

    @Query("SELECT config FROM modulelistcache WHERE codename = :codename")
    fun getConfig(codename: String): String

    @Query("SELECT changeBoot FROM modulelistcache WHERE codename = :codename")
    fun getChangeBoot(codename: String): Boolean

    @Query("SELECT mmtReborn FROM modulelistcache WHERE codename = :codename")
    fun getMmtReborn(codename: String): Boolean

    @Query("SELECT repoId FROM modulelistcache WHERE codename = :codename")
    fun getRepoId(codename: String): String

    @Query("SELECT lastUpdate FROM modulelistcache WHERE codename = :codename")
    fun getLastUpdate(codename: String): Long

    @Query("SELECT * FROM modulelistcache WHERE codename = :codename")
    fun get(codename: String): ModuleListCache
}