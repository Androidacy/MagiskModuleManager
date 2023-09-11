/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReposListDao {
    // contains
    // id (string, primary), url (string), enabled (boolean), donate (string), support (string), submitModule (string), lastUpdate (bigint), name (string) and website (string)

    // functions:
    // getAll(): List<ReposList>
    // getById(id: String): ReposList
    // insert(reposList: ReposList)
    // update(reposList: ReposList)
    // delete(reposList: ReposList)

    @Query("SELECT * FROM ReposList")
    fun getAll(): List<ReposList>

    @Query("SELECT * FROM ReposList WHERE id = :id")
    fun getById(id: String): ReposList

    @Insert(entity = ReposList::class, onConflict = OnConflictStrategy.REPLACE)
    fun insert(reposList: ReposList)

    @Query("UPDATE ReposList SET url = :url, enabled = :enabled, donate = :donate, support = :support, submitModule = :submitModule, lastUpdate = :lastUpdate, name = :name, website = :website WHERE id = :id")
    fun update(id: String = "", url: String = "", enabled: Boolean = false, donate: String = "", support: String = "", submitModule: String = "", lastUpdate: Long = 0, name: String = "", website: String = "")

    @Query("DELETE FROM ReposList WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM ReposList")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM ReposList")
    fun count(): Int

    // get fun
    @Query("SELECT url FROM ReposList WHERE id = :id")
    fun getUrl(id: String): String

    @Query("SELECT enabled FROM ReposList WHERE id = :id")
    fun getEnabled(id: String): Boolean

    @Query("SELECT donate FROM ReposList WHERE id = :id")
    fun getDonate(id: String): String

    @Query("SELECT support FROM ReposList WHERE id = :id")
    fun getSupport(id: String): String

    @Query("SELECT submitModule FROM ReposList WHERE id = :id")
    fun getSubmitModule(id: String): String

    @Query("SELECT lastUpdate FROM ReposList WHERE id = :id")
    fun getLastUpdate(id: String): Long

    @Query("SELECT name FROM ReposList WHERE id = :id")
    fun getName(id: String): String

    @Query("SELECT website FROM ReposList WHERE id = :id")
    fun getWebsite(id: String): String

    // set fun
    @Query("UPDATE ReposList SET url = :url WHERE id = :id")
    fun setUrl(id: String, url: String)

    @Query("UPDATE ReposList SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE ReposList SET donate = :donate WHERE id = :id")
    fun setDonate(id: String, donate: String)

    @Query("UPDATE ReposList SET support = :support WHERE id = :id")
    fun setSupport(id: String, support: String)

    @Query("UPDATE ReposList SET submitModule = :submitModule WHERE id = :id")
    fun setSubmitModule(id: String, submitModule: String)

    @Query("UPDATE ReposList SET lastUpdate = :lastUpdate WHERE id = :id")
    fun setLastUpdate(id: String, lastUpdate: Long)

    @Query("UPDATE ReposList SET name = :name WHERE id = :id")
    fun setName(id: String, name: String)

    @Query("UPDATE ReposList SET website = :website WHERE id = :id")
    fun setWebsite(id: String, website: String)
}