/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.repo

import androidx.room.Room
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.getPreferences
import com.fox2code.mmm.utils.io.Hashes.Companion.hashSha256
import com.fox2code.mmm.utils.io.PropUtils.Companion.isNullString
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.room.ReposList
import com.fox2code.mmm.utils.room.ReposListDatabase
import org.json.JSONObject
import timber.log.Timber
import java.nio.charset.StandardCharsets

@Suppress("MemberVisibilityCanBePrivate")
class CustomRepoManager internal constructor(
    mainApplication: MainApplication?, private val repoManager: RepoManager
) {
    private val customRepos: Array<String?> = arrayOfNulls(MAX_CUSTOM_REPOS)

    var dirty = false
    var repoCount: Int
        private set

    init {
        repoCount = 0
        // refuse to load if setup is not complete
        if (getPreferences("mmm")!!.getString("last_shown_setup", "") == "v5") {
            val i = 0
            val lastFilled = intArrayOf(0)
            // now the same as above but for room database
            val applicationContext = mainApplication!!.applicationContext
            val db = Room.databaseBuilder(
                applicationContext, ReposListDatabase::class.java, "ReposList.db"
            ).build()
            val reposListDao = db.reposListDao()
            val reposListList = reposListDao.getAll()
            for (reposList in reposListList) {
                val repo = reposList.url
                if (!isNullString(repo) && !RepoManager.isBuiltInRepo(repo)) {
                    lastFilled[0] = i
                    val index = if (AUTO_RECOMPILE) repoCount else i
                    customRepos[index] = repo
                    repoCount++
                    (repoManager.addOrGet(repo) as CustomRepoData).override = "custom_repo_$index"
                }
            }
            db.close()
        }
    }

    fun addRepo(repo: String): CustomRepoData? {
        require(!RepoManager.isBuiltInRepo(repo)) { "Can't add built-in repo to custom repos" }
        for (repoEntry in customRepos) {
            if (repo == repoEntry) return repoManager[repoEntry] as CustomRepoData
        }
        var i = 0
        while (customRepos[i] != null) i++
        customRepos[i] = repo
        // fetch that sweet sweet json
        val json: ByteArray = try {
            doHttpGet(repo, false)
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch json from repo")
            return null
        }
        // get website, support, donate, submitModule. all optional. name is required.
        // parse json
        val jsonObject: JSONObject = try {
            JSONObject(String(json))
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse json from repo")
            return null
        }
        // get name
        val name: String = try {
            jsonObject.getString("name")
        } catch (e: Exception) {
            Timber.e(e, "Failed to get name from json")
            return null
        }
        // get website
        val website: String? = try {
            jsonObject.getString("website")
        } catch (e: Exception) {
            null
        }
        // get support
        val support: String? = try {
            jsonObject.getString("support")
        } catch (e: Exception) {
            null
        }
        // get donate
        val donate: String? = try {
            jsonObject.getString("donate")
        } catch (e: Exception) {
            null
        }
        // get submitModule
        val submitModule: String? = try {
            jsonObject.getString("submitModule")
        } catch (e: Exception) {
            null
        }
        val id = "repo_" + hashSha256(repo.toByteArray(StandardCharsets.UTF_8))
        // now the same as above but for room database
        val applicationContext = INSTANCE!!.applicationContext
        val db = Room.databaseBuilder(
            applicationContext, ReposListDatabase::class.java, "ReposList.db"
        ).build()
        val reposListDao = db.reposListDao()
        val reposList = ReposList(id, repo, true, donate, support, submitModule, 0, name, website)
        reposListDao.insert(reposList)
        repoCount++
        dirty = true
        val customRepoData = repoManager.addOrGet(repo) as CustomRepoData
        customRepoData.override = "repo_$id"
        customRepoData.preferenceId = id
        customRepoData.website = website
        customRepoData.support = support
        customRepoData.donate = donate
        customRepoData.submitModule = submitModule
        customRepoData.name = name
        // Set the enabled state to true
        customRepoData.isEnabled = true
        customRepoData.updateEnabledState()
        db.close()
        return customRepoData
    }

    fun getRepo(id: String?): CustomRepoData {
        return repoManager[id] as CustomRepoData
    }

    @Suppress("SENSELESS_COMPARISON")
    fun removeRepo(index: Int) {
        val oldRepo = customRepos[index]
        if (oldRepo != null) {
            customRepos[index] = null
            repoCount--
            val customRepoData = repoManager[oldRepo] as CustomRepoData
            if (customRepoData != null) {
                customRepoData.isEnabled = false
                customRepoData.override = null
            }
            dirty = true
        }
    }

    fun hasRepo(repo: String): Boolean {
        for (repoEntry in customRepos) {
            if (repo == repoEntry) return true
        }
        return false
    }

    fun canAddRepo(): Boolean {
        return repoCount < MAX_CUSTOM_REPOS
    }

    fun canAddRepo(repo: String): Boolean {
        return if (RepoManager.isBuiltInRepo(repo) || hasRepo(repo) || !this.canAddRepo()) false else repo.startsWith(
            "https://"
        ) && repo.indexOf('/', 9) != -1
    }

    fun needUpdate(): Boolean {
        val needUpdate = dirty
        if (needUpdate) dirty = false
        return needUpdate
    }

    companion object {
        const val MAX_CUSTOM_REPOS = 5
        private const val AUTO_RECOMPILE = true
    }
}