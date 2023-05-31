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
    @JvmField
    var id: String
    @JvmField
    var name: String?
    @JvmField
    var version: String? = null
    @JvmField
    var versionCode: Long = 0
    @JvmField
    var author: String? = null
    @JvmField
    var description: String? = null
    @JvmField
    var updateJson: String? = null

    // Community meta
    @JvmField
    var changeBoot = false
    @JvmField
    var mmtReborn = false
    @JvmField
    var support: String? = null
    @JvmField
    var donate: String? = null
    @JvmField
    var config: String? = null

    // Community restrictions
    @JvmField
    var needRamdisk = false
    @JvmField
    var minMagisk = 0
    @JvmField
    var minApi = 0
    @JvmField
    var maxApi = 0

    // Module status (0 if not from Module Manager)
    @JvmField
    var flags = 0

    // Module safety (null if not provided)
    @JvmField
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