/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils

class FastException private constructor() : RuntimeException() {
    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }

    companion object {
        val INSTANCE = FastException()
    }
}