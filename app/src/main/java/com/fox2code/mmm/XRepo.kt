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