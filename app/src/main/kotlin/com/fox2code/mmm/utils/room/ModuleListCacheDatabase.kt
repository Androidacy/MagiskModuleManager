/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.room

import androidx.room.Database

@Suppress("unused")
@Database(entities = [ModuleListCache::class], version = 1)
abstract class ModuleListCacheDatabase {
    abstract fun moduleListCacheDao(): ModuleListCacheDao
}