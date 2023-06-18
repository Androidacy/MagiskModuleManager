/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Entity

@Suppress("unused")
@Entity(tableName = "modulelistcache")
class ModuleListCache (
    var codename: String,
    var version: String,
    var versionCode: Int,
    var author: String,
    var description: String,
    var minApi: Int,
    var maxApi: Int,
    var minMagisk: Int,
    var needRamdisk: Boolean,
    var support: String,
    var donate: String,
    var config: String,
    var changeBoot: Boolean,
    var mmtReborn: Boolean,
    var repoId: String,
    var lastUpdate: Long,
    val name: String,
    var safe: Boolean
) {
    // functions:
    // getAll(): List<ModuleListCache>
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
}