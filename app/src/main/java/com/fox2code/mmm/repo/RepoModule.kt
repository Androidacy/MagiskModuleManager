/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.repo

import androidx.annotation.StringRes
import com.fox2code.mmm.manager.ModuleInfo

class RepoModule {
    @JvmField
    val repoData: RepoData
    @JvmField
    val moduleInfo: ModuleInfo
    @JvmField
    val id: String
    @JvmField
    var repoName: String? = null
    @JvmField
    var lastUpdated: Long = 0
    @JvmField
    var propUrl: String? = null
    @JvmField
    var zipUrl: String? = null
    @JvmField
    var notesUrl: String? = null
    @JvmField
    var checksum: String? = null
    @JvmField
    var processed = false

    @JvmField
    @StringRes
    var qualityText = 0
    @JvmField
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
        moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
        safe = moduleInfo.safe
    }
}