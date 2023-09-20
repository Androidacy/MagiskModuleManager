/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

@Suppress("unused")
enum class Constants {
    ;

    companion object {
        const val EXTRA_DOWNLOAD_TITLE: String = "Download"
        const val MAGISK_VER_CODE_FLAT_MODULES = 19000
        const val MAGISK_VER_CODE_UTIL_INSTALL = 20400
        const val MAGISK_VER_CODE_PATH_SUPPORT = 21000
        const val MAGISK_VER_CODE_INSTALL_COMMAND = 21200
        const val MAGISK_VER_CODE_MAGISK_ZYGOTE = 24000
        const val INTENT_INSTALL_INTERNAL =
            BuildConfig.APPLICATION_ID + ".intent.action.INSTALL_MODULE_INTERNAL"
        const val INTENT_ANDROIDACY_INTERNAL =
            BuildConfig.APPLICATION_ID + ".intent.action.OPEN_ANDROIDACY_INTERNAL"
        const val EXTRA_INSTALL_PATH = "extra_install_path"
        const val EXTRA_INSTALL_NAME = "extra_install_name"
        const val EXTRA_INSTALL_CONFIG = "extra_install_config"
        const val EXTRA_INSTALL_CHECKSUM = "extra_install_checksum"
        const val EXTRA_INSTALL_MMT_REBORN = "extra_install_mmt_reborn"
        const val EXTRA_INSTALL_NO_EXTENSIONS = "extra_install_no_extensions"
        const val EXTRA_INSTALL_TEST_ROOTLESS = "extra_install_test_rootless"
        const val EXTRA_ANDROIDACY_COMPAT_LEVEL = "extra_androidacy_compat_level"
        const val EXTRA_ANDROIDACY_ALLOW_INSTALL = "extra_androidacy_allow_install"
        const val EXTRA_ANDROIDACY_ACTIONBAR_TITLE = "extra_androidacy_actionbar_title"
        const val EXTRA_ANDROIDACY_ACTIONBAR_CONFIG = "extra_androidacy_actionbar_config"
        const val EXTRA_MARKDOWN_URL = "extra_markdown_url"
        const val EXTRA_MARKDOWN_TITLE = "extra_markdown_title"
        const val EXTRA_MARKDOWN_CONFIG = "extra_markdown_config"
        const val EXTRA_MARKDOWN_CHANGE_BOOT = "extra_markdown_change_boot"
        const val EXTRA_MARKDOWN_NEEDS_RAMDISK = "extra_markdown_needs_ramdisk"
        const val EXTRA_MARKDOWN_MIN_MAGISK = "extra_markdown_min_magisk"
        const val EXTRA_MARKDOWN_MIN_API = "extra_markdown_min_api"
        const val EXTRA_MARKDOWN_MAX_API = "extra_markdown_max_api"
        const val EXTRA_FADE_OUT = "extra_fade_out"
        const val EXTRA_FROM_MANAGER = "extra_from_manager"
    }
}