/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ReposList")
data class ReposList(
    @PrimaryKey var id: String,
    var url: String,
    var enabled: Boolean,
    var donate: String,
    var support: String,
    var submitModule: String,
    var lastUpdate: Long,
    var name: String,
    var website: String
)