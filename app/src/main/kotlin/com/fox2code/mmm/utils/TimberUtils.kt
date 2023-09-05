/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils

import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.ReleaseTree
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.timber.SentryTimberTree
import timber.log.Timber
import timber.log.Timber.Forest.plant

@Suppress("UnstableApiUsage")
object TimberUtils {

    @JvmStatic
    fun configTimber() {
        // init timber
        // init timber
        if (BuildConfig.DEBUG) {
            plant(Timber.DebugTree())
        } else {
            if (MainApplication.isCrashReportingEnabled) {
                plant(
                    SentryTimberTree(
                        Sentry.getCurrentHub(),
                        SentryLevel.WARNING,
                        SentryLevel.WARNING
                    )
                )
            } else {
                plant(ReleaseTree())
            }
        }
    }
}