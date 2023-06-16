/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Suppress("unused")
@Database(entities = [ReposList::class], version = 1)
abstract class ReposListDatabase : RoomDatabase() {
    abstract fun reposListDao(): ReposListDao
}