package com.fox2code.mmm.markdown

import androidx.annotation.StringRes
import com.fox2code.mmm.R

enum class MarkdownChip(
    @field:StringRes @param:StringRes val title: Int,
    @field:StringRes @param:StringRes val desc: Int
) {
    CHANGE_BOOT(
        R.string.module_can_change_boot,
        R.string.module_can_change_boot_desc
    ),
    NEED_RAMDISK(
        R.string.module_needs_ramdisk, R.string.module_needs_ramdisk_desc
    ),
    MIN_MAGISK(R.string.module_min_magisk_chip, 0), MIN_SDK(
        R.string.module_min_sdk_chip, 0
    ),
    MAX_SDK(R.string.module_max_sdk_chip, 0)
}