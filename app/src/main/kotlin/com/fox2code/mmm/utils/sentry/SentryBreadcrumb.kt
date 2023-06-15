/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.sentry

import io.sentry.Breadcrumb
import io.sentry.SentryLevel
import java.util.Objects

class SentryBreadcrumb {
    @JvmField
    val breadcrumb: Breadcrumb = Breadcrumb()

    init {
        breadcrumb.level = SentryLevel.INFO
    }

    fun setType(type: String?) {
        breadcrumb.type = type
    }

    fun setData(key: String, value: Any?) {
        @Suppress("NAME_SHADOWING") var value = value
        if (value == null) value = "null"
        Objects.requireNonNull(key)
        breadcrumb.setData(key, value)
    }

    fun setCategory(category: String?) {
        breadcrumb.category = category
    }
}