/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.repo

import androidx.annotation.StringRes
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.manager.ModuleInfo

class RepoModule {
    val repoData: RepoData

    val moduleInfo: ModuleInfo

    val id: String

    var repoName: String? = null

    var lastUpdated: Long = 0

    var propUrl: String? = null

    var zipUrl: String? = null

    var notesUrl: String? = null

    var checksum: String? = null

    var processed = false

    @StringRes
    var qualityText = 0

    var qualityValue = 0
    var safe: Boolean

    constructor(repoData: RepoData, id: String) {
        this.repoData = repoData
        moduleInfo = ModuleInfo(id)
        this.id = id
        moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
        safe = moduleInfo.safe
    }

    // allows all fields to be set-
    constructor(
        repoData: RepoData,
        id: String,
        name: String?,
        description: String?,
        author: String?,
        donate: String?,
        config: String?,
        support: String?,
        version: String?,
        versionCode: Int
    ) {
        this.repoData = repoData
        moduleInfo = ModuleInfo(id)
        this.id = id
        moduleInfo.name = name
        moduleInfo.description = description
        moduleInfo.author = author
        moduleInfo.donate = donate
        moduleInfo.config = config
        moduleInfo.support = support
        moduleInfo.version = version
        moduleInfo.versionCode = versionCode.toLong()
        moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID and ModuleInfo.FLAG_MM_REMOTE_MODULE
        safe = moduleInfo.safe
        // if mainapplication.repomodules has this module, set the flag for remote module
        if (MainApplication.getInstance().repoModules.containsKey(id)) {
            moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_MM_REMOTE_MODULE
        }
    }
}