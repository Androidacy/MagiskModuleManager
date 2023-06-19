/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Entity

@Entity(tableName = "ReposList", primaryKeys = ["id"], indices = [androidx.room.Index(value = ["id"], unique = true)])
data class ReposList(
    var id: String,
    var url: String,
    var enabled: Boolean,
    var donate: String?,
    var support: String?,
    var submitModule: String?,
    var lastUpdate: Int,
    var name: String,
    var website: String?
)