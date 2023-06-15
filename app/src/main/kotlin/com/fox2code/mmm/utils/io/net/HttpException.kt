/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.io.net

import androidx.annotation.Keep
import java.io.IOException

class HttpException : IOException {
    val errorCode: Int

    internal constructor(text: String?, errorCode: Int) : super(text) {
        this.errorCode = errorCode
    }

    @Keep
    constructor(errorCode: Int) : super("Received error code: $errorCode") {
        this.errorCode = errorCode
    }

    fun shouldTimeout(): Boolean {
        return when (errorCode) {
            419, 429, 503 -> true
            else -> false
        }
    }

    companion object {
        @JvmStatic
        fun shouldTimeout(exception: Exception?): Boolean {
            return exception is HttpException &&
                    exception.shouldTimeout()
        }
    }
}