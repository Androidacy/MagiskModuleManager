/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress(
    "KotlinConstantConditions", "ktConcatNullable"
)

package com.fox2code.mmm

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.module.ModuleViewListBuilder
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.IntentHelper.Companion.OnFileReceivedCallback
import com.fox2code.mmm.utils.io.net.Http
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.io.File
import java.util.Date


@Suppress("SameParameterValue")
enum class NotificationType(
    @field:StringRes @param:StringRes @JvmField val textId: Int,
    @field:DrawableRes @JvmField val iconId: Int,
    @field:AttrRes @JvmField val backgroundAttr: Int = androidx.appcompat.R.attr.colorError,
    @field:AttrRes @JvmField val foregroundAttr: Int = com.google.android.material.R.attr.colorOnPrimary,
    val onClickListener: View.OnClickListener? = null,
    var special: Boolean = false
) : NotificationTypeCst {

    DEBUG(
        R.string.debug_build,
        R.drawable.ic_baseline_bug_report_24,
        com.google.android.material.R.attr.colorPrimary,
        com.google.android.material.R.attr.colorOnPrimary,
        // on click show a toast formatted with commit hash and build date, plus number of days before expiration (builds expire 30 days after build date)
        View.OnClickListener { v: View ->
            val buildTime = BuildConfig.BUILD_TIME
            val buildTimeDays = (System.currentTimeMillis() - buildTime) / 86400000
            // builddatepretty is created from build_time as YYYY-MM-DD
            val sdf = android.icu.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val netDate = Date(buildTime)
            val buildDatePretty = sdf.format(netDate)
            val toastText = v.context.getString(
                R.string.debug_build_toast,
                BuildConfig.COMMIT_HASH,
                buildDatePretty,
                30 - buildTimeDays
            )
            Toast.makeText(
                v.context, toastText, Toast.LENGTH_LONG
            ).show()
        }) {
        override fun shouldRemove(): Boolean {
            return !BuildConfig.DEBUG
        }
    },

    SHOWCASE_MODE(
        R.string.showcase_mode,
        R.drawable.ic_baseline_lock_24,
        com.google.android.material.R.attr.colorPrimary,
        com.google.android.material.R.attr.colorOnPrimary
    ) {
        override fun shouldRemove(): Boolean {
            return !MainApplication.isShowcaseMode
        }
    },


    NO_ROOT(R.string.fail_root_magisk, R.drawable.ic_baseline_numbers_24) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.errorNotification !== this
        }
    },
    ROOT_DENIED(R.string.fail_root_denied, R.drawable.ic_baseline_numbers_24) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.errorNotification !== this
        }
    },


    MAGISK_OUTDATED(
        R.string.magisk_outdated,
        R.drawable.ic_baseline_update_24,
        View.OnClickListener { v: View ->
            IntentHelper.openUrl(
                v.context, "https://github.com/topjohnwu/Magisk/releases"
            )
        }) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.isKsu || InstallerInitializer.peekMagiskPath() == null || InstallerInitializer.peekMagiskVersion() >= Constants.MAGISK_VER_CODE_INSTALL_COMMAND
        }
    },


    NO_INTERNET(R.string.fail_internet, R.drawable.ic_baseline_cloud_off_24) {
        override fun shouldRemove(): Boolean {
            return RepoManager.getINSTANCE()!!.hasConnectivity()
        }
    },


    REPO_UPDATE_FAILED(R.string.repo_update_failed, R.drawable.ic_baseline_cloud_off_24) {
        override fun shouldRemove(): Boolean {
            return RepoManager.getINSTANCE()!!.isLastUpdateSuccess
        }
    },


    NEED_CAPTCHA_ANDROIDACY(
        R.string.androidacy_need_captcha,
        R.drawable.ic_baseline_refresh_24,
        View.OnClickListener { v: View ->
            IntentHelper.openUrlAndroidacy(
                v.context, "https://" + Http.needCaptchaAndroidacyHost() + "/", false
            )
        }) {
        override fun shouldRemove(): Boolean {
            return (!RepoManager.isAndroidacyRepoEnabled || !Http.needCaptchaAndroidacy())
        }
    },


    NO_WEB_VIEW(R.string.no_web_view, R.drawable.ic_baseline_android_24) {
        override fun shouldRemove(): Boolean {
            return Http.hasWebView()
        }
    },


    UPDATE_AVAILABLE(
        R.string.app_update_available,
        R.drawable.ic_baseline_system_update_24,
        androidx.appcompat.R.attr.colorPrimary,
        com.google.android.material.R.attr.colorOnPrimary,
        View.OnClickListener { v: View ->
            // launch update activity with action download
            val pendingIntent = android.app.PendingIntent.getActivity(
                v.context,
                0,
                Intent(
                    v.context, UpdateActivity::class.java
                ).setAction(UpdateActivity.ACTIONS.DOWNLOAD.toString()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            try {
                pendingIntent.send()
            } catch (e: android.app.PendingIntent.CanceledException) {
                Timber.e(e)
            }
        },
        false
    ) {
        override fun shouldRemove(): Boolean {
            return !AppUpdateManager.appUpdateManager.peekShouldUpdate()
        }
    },


    INSTALL_FROM_STORAGE(
        R.string.install_from_storage,
        R.drawable.ic_baseline_storage_24,
        androidx.appcompat.R.attr.colorBackgroundFloating,
        com.google.android.material.R.attr.colorOnBackground,
        View.OnClickListener { v: View? ->
            if (MainApplication.getPreferences("mmm")
                    ?.getBoolean("pref_require_security", false) == true
            ) {
                // block local install for safety
                MaterialAlertDialogBuilder(v!!.context).setTitle(R.string.install_from_storage)
                    .setMessage(R.string.install_from_storage_safe_modules)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@OnClickListener
            }
            val compatActivity = MainApplication.getInstance().lastActivity!!
            val module = File(
                compatActivity.cacheDir, "installer" + File.separator + "module.zip"
            )
            IntentHelper.openFileTo(module, object : OnFileReceivedCallback {

                override fun onReceived(
                    target: File?, uri: Uri?, response: Int
                ) {
                    Companion
                    if (response == IntentHelper.RESPONSE_FILE) {
                        // ensure file exists
                        if (!target!!.exists()) {
                            Toast.makeText(
                                compatActivity,
                                R.string.install_from_storage_file_not_found,
                                Toast.LENGTH_LONG
                            ).show()
                            return
                        }
                        IntentHelper.openInstaller(
                            compatActivity, target.absolutePath, compatActivity.getString(
                                R.string.local_install_title
                            ), null, null, false, BuildConfig.DEBUG &&  // Use debug mode if no root
                                    InstallerInitializer.peekMagiskPath() == null
                        )
                    } else if (response == IntentHelper.RESPONSE_URL) {
                        IntentHelper.openInstaller(
                            compatActivity, uri.toString(), compatActivity.getString(
                                R.string.remote_install_title
                            ), null, null, false, BuildConfig.DEBUG &&  // Use debug mode if no root
                                    InstallerInitializer.peekMagiskPath() == null
                        )
                    }
                }
            })
        },
        false
    ) {
        override fun shouldRemove(): Boolean {
            return !BuildConfig.DEBUG && (MainApplication.isShowcaseMode || InstallerInitializer.peekMagiskPath() == null)
        }
    },
    KSU_EXPERIMENTAL(
        R.string.ksu_experimental,
        R.drawable.ic_baseline_warning_24,
        androidx.appcompat.R.attr.colorError,
        com.google.android.material.R.attr.colorOnPrimary,
        null,
        false
    ) {
        override fun shouldRemove(): Boolean {
            return !BuildConfig.DEBUG && (MainApplication.isShowcaseMode || InstallerInitializer.peekMagiskPath() == null || !InstallerInitializer.isKsu)
        }
    };

    constructor(@StringRes textId: Int, iconId: Int, onClickListener: View.OnClickListener) : this(
        textId,
        iconId,
        androidx.appcompat.R.attr.colorError,
        com.google.android.material.R.attr.colorOnPrimary,
        onClickListener
    )

    open fun shouldRemove(): Boolean {
        // By default, remove the notification`
        return false
    }

    fun autoAdd(moduleViewListBuilder: ModuleViewListBuilder) {
        if (!shouldRemove()) moduleViewListBuilder.addNotification(this)
    }

    companion object

}
