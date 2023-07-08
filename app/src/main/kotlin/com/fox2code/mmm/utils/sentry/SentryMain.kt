/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.sentry

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Process
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.CrashHandler
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.hideToken
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyLink
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions.BeforeBreadcrumbCallback
import io.sentry.SentryOptions.BeforeSendCallback
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.android.fragment.FragmentLifecycleIntegration
import io.sentry.android.timber.SentryTimberIntegration
import io.sentry.protocol.SentryId
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber

object SentryMain {
    const val IS_SENTRY_INSTALLED = true
    private var isCrashing = false
    private var isSentryEnabled = false
    private var crashExceptionId: SentryId? = null

    /**
     * Initialize Sentry
     * Sentry is used for crash reporting and performance monitoring.
     */
    @JvmStatic
    @SuppressLint("RestrictedApi", "UnspecifiedImmutableFlag", "ApplySharedPref")
    fun initialize(mainApplication: MainApplication) {
        Thread.setDefaultUncaughtExceptionHandler { _: Thread?, throwable: Throwable ->
            isCrashing = true
            MainApplication.clearCachedSharedPrefs()
            TrackHelper.track().exception(throwable).with(MainApplication.INSTANCE!!.tracker)
            // open crash handler and exit
            val intent = Intent(mainApplication, CrashHandler::class.java)
            // pass the entire exception to the crash handler
            intent.putExtra("exception", throwable)
            // add stacktrace as string
            intent.putExtra("stacktrace", throwable.stackTrace)
            // serialize Sentry.captureException and pass it to the crash handler
            intent.putExtra("sentryException", throwable)
            // pass crashReportingEnabled to crash handler
            intent.putExtra("crashReportingEnabled", isSentryEnabled)
            // add isCrashing to intent
            intent.putExtra("isCrashing", true)
            // add crashExceptionId to intent
            if (crashExceptionId != null) {
                intent.putExtra("lastEventId", crashExceptionId!!.toString())
            } else {
                intent.putExtra("lastEventId", "")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Timber.e("Starting crash handler")
            mainApplication.startActivity(intent)
            Timber.e("Exiting")
            Process.killProcess(Process.myPid())
        }
        // If first_launch pref is not false, refuse to initialize Sentry
        val sharedPreferences = MainApplication.getSharedPreferences("mmm")!!
        if (sharedPreferences.getString("last_shown_setup", null) != "v3") {
            return
        }
        isSentryEnabled = sharedPreferences.getBoolean("pref_crash_reporting_enabled", false)
        // set sentryEnabled on preference change of pref_crash_reporting_enabled
        sharedPreferences.registerOnSharedPreferenceChangeListener { sharedPreferences1: SharedPreferences, s: String ->
            if (s == "pref_crash_reporting_enabled") {
                isSentryEnabled = sharedPreferences1.getBoolean(s, false)
            }
        }
        SentryAndroid.init(mainApplication) { options: SentryAndroidOptions ->
            // If crash reporting is disabled, stop here.
            if (!MainApplication.isCrashReportingEnabled) {
                isSentryEnabled = false // Set sentry state to disabled
                options.dsn = ""
            } else {
                // get pref_crash_reporting_pii pref
                val crashReportingPii = sharedPreferences.getBoolean("crashReportingPii", false)
                isSentryEnabled = true // Set sentry state to enabled
                options.addIntegration(
                    FragmentLifecycleIntegration(
                        mainApplication,
                        enableFragmentLifecycleBreadcrumbs = true,
                        enableAutoFragmentLifecycleTracing = true
                    )
                )
                // Enable automatic activity lifecycle breadcrumbs
                options.isEnableActivityLifecycleBreadcrumbs = true
                // Enable automatic fragment lifecycle breadcrumbs
                options.addIntegration(SentryTimberIntegration())
                options.isCollectAdditionalContext = true
                options.isAttachThreads = true
                options.isAttachStacktrace = true
                options.isEnableNdk = true
                options.addInAppInclude("com.fox2code.mmm")
                options.addInAppInclude("com.fox2code.mmm.debug")
                options.addInAppInclude("com.fox2code.mmm.fdroid")
                options.addInAppExclude("com.fox2code.mmm.utils.sentry.SentryMain")
                options.addInAppInclude("com.fox2code.mmm.utils")
                // Respect user preference for sending PII. default is true on non fdroid builds, false on fdroid builds
                options.isSendDefaultPii = crashReportingPii
                options.enableAllAutoBreadcrumbs(true)
                // in-app screenshots are only sent if the app crashes, and it only shows the last activity. so no, we won't see your, ahem, "private" stuff
                options.isAttachScreenshot = true
                // It just tell if sentry should ping the sentry dsn to tell the app is running. Useful for performance and profiling.
                options.isEnableAutoSessionTracking = true
                // disable crash tracking - we handle that ourselves
                options.isEnableUncaughtExceptionHandler = true
                // Add a callback that will be used before the event is sent to Sentry.
                // With this callback, you can modify the event or, when returning null, also discard the event.
                options.environment = BuildConfig.BUILD_TYPE
                options.beforeSend = BeforeSendCallback { event: SentryEvent?, _: Hint? ->
                    // in the rare event that crash reporting has been disabled since we started the app, we don't want to send the crash report
                    if (!isSentryEnabled) {
                        return@BeforeSendCallback null
                    }
                    crashExceptionId = event?.eventId
                    event
                }
                // Filter breadcrumb content from crash report.
                options.beforeBreadcrumb =
                    BeforeBreadcrumbCallback { breadcrumb: Breadcrumb, _: Hint? ->
                        val url = breadcrumb.getData("url") as String?
                        if (url.isNullOrEmpty()) return@BeforeBreadcrumbCallback null
                        if ("cloudflare-dns.com" == Uri.parse(url).host) {
                            return@BeforeBreadcrumbCallback null
                        }
                        if (isAndroidacyLink(url)) {
                            breadcrumb.setData("url", hideToken(url))
                        }
                        breadcrumb
                    }
            }
        }
    }

    fun addSentryBreadcrumb(sentryBreadcrumb: SentryBreadcrumb) {
        if (MainApplication.isCrashReportingEnabled) {
            Sentry.addBreadcrumb(sentryBreadcrumb.breadcrumb)
        }
    }
}
