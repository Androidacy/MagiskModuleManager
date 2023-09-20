/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.fox2code.mmm.AppUpdateManager
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.UpdateActivity
import com.fox2code.mmm.manager.ModuleManager
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.utils.io.PropUtils
import timber.log.Timber
import java.util.Objects
import java.util.concurrent.TimeUnit

class BackgroundUpdateChecker(context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams) {
    override fun doWork(): Result {
        if (!NotificationManagerCompat.from(this.applicationContext)
                .areNotificationsEnabled() || !MainApplication.isBackgroundUpdateCheckEnabled
        ) return Result.success()
        synchronized(lock) { doCheck(this.applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "background_update"
        private const val NOTIFICATION_ID = 1
        private const val NOTFIICATION_GROUP = "updates"
        private const val NOTIFICATION_CHANNEL_ID_APP = "background_update_app"
        val lock =
            Any() // Avoid concurrency issues
        private const val NOTIFICATION_ID_ONGOING = 2
        private const val NOTIFICATION_CHANNEL_ID_ONGOING = "mmm_background_update"
        private const val NOTIFICATION_ID_APP = 3

        @SuppressLint("RestrictedApi")
        private fun postNotificationForAppUpdate(context: Context) {
            // create the notification channel if not already created
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    NOTIFICATION_CHANNEL_ID_APP, NotificationManagerCompat.IMPORTANCE_HIGH
                ).setName(
                    context.getString(
                        R.string.notification_channel_category_app_update
                    )
                ).setDescription(
                    context.getString(
                        R.string.notification_channel_category_app_update_description
                    )
                ).setGroup(
                    NOTFIICATION_GROUP
                ).build()
            )
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID_APP)
            builder.setSmallIcon(R.drawable.baseline_system_update_24)
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            builder.setShowWhen(false)
            builder.setOnlyAlertOnce(true)
            builder.setOngoing(false)
            builder.setAutoCancel(true)
            builder.setGroup(NOTFIICATION_GROUP)
            // open app on click
            val intent = Intent(context, UpdateActivity::class.java)
            // set action to ACTIONS.DOWNLOAD
            intent.action = UpdateActivity.ACTIONS.DOWNLOAD.toString()
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // set summary to Found X
            builder.setContentTitle(context.getString(R.string.notification_channel_background_update_app))
            builder.setContentText(context.getString(R.string.notification_channel_background_update_app_description))
            if (ContextCompat.checkSelfPermission(
                    MainApplication.INSTANCE!!.applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notificationManager.notify(NOTIFICATION_ID_APP, builder.build())
            }
        }

        @Suppress("NAME_SHADOWING", "KotlinConstantConditions")
        fun doCheck(context: Context) {
            // first, check if the user has enabled background update checking
            if (!MainApplication.getSharedPreferences("mmm")!!
                    .getBoolean("pref_background_update_check", false)
            ) {
                return
            }
            if (MainApplication.INSTANCE!!.isInForeground) {
                // don't check if app is in foreground, this is a background check
                return
            }
            // next, check if user requires wifi
            if (MainApplication.getSharedPreferences("mmm")!!
                    .getBoolean("pref_background_update_check_wifi", true)
            ) {
                // check if wifi is connected
                val connectivityManager =
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val networkInfo = connectivityManager.activeNetwork
                if (networkInfo == null || !connectivityManager.getNetworkCapabilities(
                        networkInfo
                    )?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)!!
                ) {
                    Timber.w("Background update check: wifi not connected but required")
                    return
                }
            }
            synchronized(lock) {

                // post checking notification if notifications are enabled
                if (ContextCompat.checkSelfPermission(
                        MainApplication.INSTANCE!!.applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val notificationManager = NotificationManagerCompat.from(context)
                    notificationManager.createNotificationChannel(
                        NotificationChannelCompat.Builder(
                            NOTIFICATION_CHANNEL_ID_ONGOING,
                            NotificationManagerCompat.IMPORTANCE_MIN
                        ).setName(
                            context.getString(
                                R.string.notification_channel_category_background_update
                            )
                        ).setDescription(
                            context.getString(
                                R.string.notification_channel_category_background_update_description
                            )
                        ).setGroup(
                            NOTFIICATION_GROUP
                        ).build()
                    )
                    val builder =
                        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID_ONGOING)
                    builder.setSmallIcon(R.drawable.ic_baseline_update_24)
                    builder.priority = NotificationCompat.PRIORITY_MIN
                    builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
                    builder.setShowWhen(false)
                    builder.setOnlyAlertOnce(true)
                    builder.setOngoing(true)
                    builder.setAutoCancel(false)
                    builder.setGroup("update_bg")
                    builder.setContentTitle(context.getString(R.string.notification_channel_background_update))
                    builder.setContentText(context.getString(R.string.notification_channel_background_update_description))
                    notificationManager.notify(NOTIFICATION_ID_ONGOING, builder.build())
                } else {
                    if (MainApplication.forceDebugLogging) Timber.d("Not posting notification because of missing permission")
                }
                ModuleManager.instance!!.scanAsync()
                RepoManager.getINSTANCE()!!.update(null)
                ModuleManager.instance!!.runAfterScan {
                    var moduleUpdateCount = 0
                    val repoModules = RepoManager.getINSTANCE()!!.modules
                    // hashmap of updateable modules names
                    val updateableModules = HashMap<String, String>()
                    for (localModuleInfo in ModuleManager.instance!!.modules.values) {
                        if ("twrp-keep" == localModuleInfo.id) continue
                        // exclude all modules with id's stored in the pref pref_background_update_check_excludes
                        try {
                            if (Objects.requireNonNull(
                                    MainApplication.getSharedPreferences("mmm")!!.getStringSet(
                                        "pref_background_update_check_excludes",
                                        HashSet()
                                    )
                                ).contains(localModuleInfo.id)
                            ) continue
                        } catch (ignored: Exception) {
                        }
                        // now, we just had to make it more fucking complicated, didn't we?
                        // we now have pref_background_update_check_excludes_version, which is a id:version stringset of versions the user may want to "skip"
                        // oh, and because i hate myself, i made ^ at the beginning match that version and newer, and $ at the end match that version and older
                        val stringSet = MainApplication.getSharedPreferences("mmm")!!.getStringSet(
                            "pref_background_update_check_excludes_version",
                            HashSet()
                        )
                        var version = ""
                        for (s in stringSet!!) {
                            if (s.startsWith(localModuleInfo.id)) {
                                version = s
                                if (MainApplication.forceDebugLogging) Timber.d("igV: %s", version)
                                break
                            }
                        }
                        val repoModule = repoModules[localModuleInfo.id]
                        localModuleInfo.checkModuleUpdate()
                        var remoteVersionCode = localModuleInfo.updateVersionCode.toString()
                        if (repoModule != null) {
                            remoteVersionCode = repoModule.moduleInfo.versionCode.toString()
                        }
                        if (version.isNotEmpty()) {
                            if (MainApplication.forceDebugLogging) Timber.d("igV found: %s", version)
                            val remoteVersionCodeInt = remoteVersionCode.toInt()
                            val wantsVersion =
                                version.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                                    .toTypedArray()[1].replace("[^0-9]".toRegex(), "").toInt()
                            // now find out if user wants up to and including this version, or this version and newer
                            // if it starts with ^, it's this version and newer, if it ends with $, it's this version and older
                            version = version.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[1]
                            if (version.startsWith("^")) {
                                if (MainApplication.forceDebugLogging) Timber.d("igV: newer")
                                // the wantsversion and newer
                                if (remoteVersionCodeInt >= wantsVersion) {
                                    if (MainApplication.forceDebugLogging) Timber.d("igV: skipping")
                                    // if it is, we skip it
                                    continue
                                }
                            } else if (version.endsWith("$")) {
                                if (MainApplication.forceDebugLogging) Timber.d("igV: older")
                                // this wantsversion and older
                                if (remoteVersionCodeInt <= wantsVersion) {
                                    if (MainApplication.forceDebugLogging) Timber.d("igV: skipping")
                                    // if it is, we skip it
                                    continue
                                }
                            } else if (wantsVersion == remoteVersionCodeInt) {
                                if (MainApplication.forceDebugLogging) Timber.d("igV: equal")
                                // if it is, we skip it
                                continue
                            }
                        }
                        if (localModuleInfo.updateVersionCode > localModuleInfo.versionCode && !PropUtils.isNullString(
                                localModuleInfo.updateVersion
                            )
                        ) {
                            moduleUpdateCount++
                            val version: String = localModuleInfo.version!!
                            val name: String = localModuleInfo.name!!
                            updateableModules[name] = version
                        } else if (repoModule != null && repoModule.moduleInfo.versionCode > localModuleInfo.versionCode && !PropUtils.isNullString(
                                repoModule.moduleInfo.version
                            )
                        ) {
                            val version: String = localModuleInfo.version!!
                            val name: String = localModuleInfo.name!!
                            moduleUpdateCount++
                            updateableModules[name] = version
                        }
                    }
                    if (moduleUpdateCount != 0) {
                        if (MainApplication.forceDebugLogging) Timber.d("Found %d updates", moduleUpdateCount)
                        postNotification(context, updateableModules, moduleUpdateCount, false)
                    }
                }
                // check for app updates
                if (MainApplication.getSharedPreferences("mmm")!!
                        .getBoolean("pref_background_update_check_app", false)
                ) {

                    // don't check if app is from play store or fdroid
                    if (BuildConfig.FLAVOR != "play" || BuildConfig.FLAVOR != "fdroid") {
                        try {
                            val shouldUpdate = AppUpdateManager.appUpdateManager.checkUpdate(true)
                            if (shouldUpdate) {
                                if (MainApplication.forceDebugLogging) Timber.d("Found app update")
                                postNotificationForAppUpdate(context)
                            } else {
                                if (MainApplication.forceDebugLogging) Timber.d("No app update found")
                            }
                        } catch (e: Exception) {
                            Timber.e("Failed to check for app update")
                        }
                    }
                }
                // remove checking notification
                if (ContextCompat.checkSelfPermission(
                        MainApplication.INSTANCE!!.applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    if (MainApplication.forceDebugLogging) Timber.d("Removing notification")
                    val notificationManager = NotificationManagerCompat.from(context)
                    notificationManager.cancel(NOTIFICATION_ID_ONGOING)
                }
            }
            // increment or create counter in shared preferences
            MainApplication.getSharedPreferences("mmm")!!.edit().putInt(
                "pref_background_update_counter",
                MainApplication.getSharedPreferences("mmm")!!
                    .getInt("pref_background_update_counter", 0) + 1
            ).apply()
        }

        fun postNotification(
            context: Context,
            updateable: HashMap<String, String>,
            updateCount: Int,
            test: Boolean
        ) {
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            builder.setSmallIcon(R.drawable.baseline_system_update_24)
            builder.priority = NotificationCompat.PRIORITY_HIGH
            builder.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            builder.setShowWhen(false)
            builder.setOnlyAlertOnce(true)
            builder.setOngoing(false)
            builder.setAutoCancel(true)
            builder.setGroup(NOTFIICATION_GROUP)
            // open app on click
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // set summary to Found X updates: <module name> <module version> <module name> <module version> ...
            val summary = StringBuilder()
            summary.append(context.getString(R.string.notification_update_summary))
            // use notification_update_module_template string to set name and version
            for ((key, value) in updateable) {
                summary.append("\n").append(
                    context.getString(
                        R.string.notification_update_module_template,
                        key,
                        value
                    )
                )
            }
            builder.setContentTitle(
                context.getString(
                    R.string.notification_update_title,
                    updateCount
                )
            )
            builder.setContentText(summary)
            // set long text to summary so it doesn't get cut off
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            if (ContextCompat.checkSelfPermission(
                    MainApplication.INSTANCE!!.applicationContext,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            // check if app is in foreground. if so, don't show notification
            if (MainApplication.INSTANCE!!.isInForeground && !test) return
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }

        fun onMainActivityCreate(context: Context) {
            // Refuse to run if first_launch pref is not false
            if (MainApplication.getSharedPreferences("mmm")!!
                    .getString("last_shown_setup", null) != "v5"
            ) return
            // create notification channel group
            val groupName: CharSequence = context.getString(R.string.notification_group_updates)
            val mNotificationManager =
                ContextCompat.getSystemService(context, NotificationManager::class.java)
            mNotificationManager?.createNotificationChannelGroup(
                NotificationChannelGroup(
                    NOTFIICATION_GROUP, groupName
                )
            )
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            notificationManagerCompat.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH
                ).setShowBadge(true).setName(
                    context.getString(
                        R.string.notification_update_pref
                    )
                ).setDescription(context.getString(R.string.auto_updates_notifs)).setGroup(
                    NOTFIICATION_GROUP
                ).build()
            )
            notificationManagerCompat.cancel(NOTIFICATION_ID)
            // now for the ongoing notification
            notificationManagerCompat.createNotificationChannel(
                NotificationChannelCompat.Builder(
                    NOTIFICATION_CHANNEL_ID_ONGOING, NotificationManagerCompat.IMPORTANCE_MIN
                ).setShowBadge(true).setName(
                    context.getString(
                        R.string.notification_update_pref
                    )
                ).setDescription(context.getString(R.string.auto_updates_notifs)).setGroup(
                    NOTFIICATION_GROUP
                ).build()
            )
            notificationManagerCompat.cancel(NOTIFICATION_ID_ONGOING)
            if (MainApplication.forceDebugLogging) Timber.d("Scheduling periodic background check")
            // use pref_background_update_check_frequency to set frequency. value is in minutes
            val frequency = MainApplication.getSharedPreferences("mmm")!!
                .getInt("pref_background_update_check_frequency", 60).toLong()
            if (MainApplication.forceDebugLogging) Timber.d("Frequency: $frequency minutes")
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "background_checker",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequest.Builder(
                    BackgroundUpdateChecker::class.java, frequency, TimeUnit.MINUTES
                ).setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build()
                ).build()
            )
        }

        fun onMainActivityResume(context: Context?) {
            NotificationManagerCompat.from(context!!).cancel(NOTIFICATION_ID)
        }
    }
}