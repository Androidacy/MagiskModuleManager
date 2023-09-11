/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.manager

import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.utils.io.PropUtils

/**
 * Representation of the module.prop
 * Optionally flags represent module status
 * It's value is 0 if not applicable
 */
open class ModuleInfo {
    // Magisk standard
    var id: String

    var name: String?

    var version: String? = null

    var versionCode: Long = 0

    var author: String? = null

    var description: String? = null

    var updateJson: String? = null

    // Community meta
    var changeBoot = false

    var mmtReborn = false

    var support: String? = null

    var donate: String? = null

    var config: String? = null

    // Community restrictions
    var needRamdisk = false

    var minMagisk = 0

    var minApi = 0

    var maxApi = 0

    // Module status (0 if not from Module Manager)
    var flags = 0

    // Module safety (null if not provided)
    var safe = false

    constructor(id: String) {
        this.id = id
        name = id
    }

    constructor(moduleInfo: ModuleInfo) {
        id = moduleInfo.id
        name = moduleInfo.name
        version = moduleInfo.version
        versionCode = moduleInfo.versionCode
        author = moduleInfo.author
        description = moduleInfo.description
        updateJson = moduleInfo.updateJson
        changeBoot = moduleInfo.changeBoot
        mmtReborn = moduleInfo.mmtReborn
        support = moduleInfo.support
        donate = moduleInfo.donate
        config = moduleInfo.config
        needRamdisk = moduleInfo.needRamdisk
        minMagisk = moduleInfo.minMagisk
        minApi = moduleInfo.minApi
        maxApi = moduleInfo.maxApi
        flags = moduleInfo.flags
        safe = moduleInfo.safe
    }

    fun hasFlag(flag: Int): Boolean {
        return flags and flag != 0
    }

    fun verify() {
        if (BuildConfig.DEBUG) {
            require(!PropUtils.isNullString(name)) {
                "name=" +
                        if (name == null) "null" else "\"" + name + "\""
            }
            require(flags and FLAG_FENCE == 0) {
                "flags=${Integer.toHexString(flags)}"
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        const val FLAG_MODULE_DISABLED = 0x01
        const val FLAG_MODULE_UPDATING = 0x02
        const val FLAG_MODULE_ACTIVE = 0x04
        const val FLAG_MODULE_UNINSTALLING = 0x08
        const val FLAG_MODULE_UPDATING_ONLY = 0x10
        const val FLAG_MODULE_MAYBE_ACTIVE = 0x20
        const val FLAG_MODULE_HAS_ACTIVE_MOUNT = 0x40
        const val FLAGS_MODULE_ACTIVE = FLAG_MODULE_ACTIVE or FLAG_MODULE_MAYBE_ACTIVE
        const val FLAG_METADATA_INVALID = -0x80000000
        const val FLAG_CUSTOM_INTERNAL = 0x40000000
        const val FLAG_MM_REMOTE_MODULE = 0x20000000
        private const val FLAG_FENCE = 0x10000000 // Should never be set
    }
}