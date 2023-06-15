/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import androidx.annotation.Keep

/**
 * Class made to expose some repo functions to xposed modules.
 * It will not be obfuscated on release builds
 */
@Keep
abstract class XRepo {
    @get:Keep
    abstract val isEnabledByDefault: Boolean

    @get:Keep
    @set:Keep
    abstract var isEnabled: Boolean

    @get:Keep
    abstract val name: String?
}