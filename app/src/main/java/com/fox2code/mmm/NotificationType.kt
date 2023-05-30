@file:Suppress("KotlinConstantConditions", "UNINITIALIZED_ENUM_COMPANION_WARNING",
    "ktConcatNullable", "BlockingMethodInNonBlockingContext"
)

package com.fox2code.mmm

import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.module.ModuleViewListBuilder
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.io.Files.Companion.patchModuleSimple
import com.fox2code.mmm.utils.io.Files.Companion.read
import com.fox2code.mmm.utils.io.net.Http
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile


enum class NotificationType constructor(
    @field:StringRes @param:StringRes @JvmField val textId: Int,
    @field:DrawableRes @JvmField val iconId: Int,
    @field:AttrRes @JvmField val backgroundAttr: Int = androidx.appcompat.R.attr.colorError,
    @field:AttrRes @JvmField val foregroundAttr: Int = com.google.android.material.R.attr.colorOnPrimary,
    @JvmField val onClickListener: View.OnClickListener? = null,
    @JvmField var special: Boolean = false
) : NotificationTypeCst {

    @JvmStatic
    DEBUG(
        R.string.debug_build,
        R.drawable.ic_baseline_bug_report_24,
        com.google.android.material.R.attr.colorSecondary,
        com.google.android.material.R.attr.colorOnSecondary
    ) {
        override fun shouldRemove(): Boolean {
            return !BuildConfig.DEBUG
        }
    },
    @JvmStatic
    SHOWCASE_MODE(
        R.string.showcase_mode, R.drawable.ic_baseline_lock_24,
        androidx.appcompat.R.attr.colorPrimary, com.google.android.material.R.attr.colorOnPrimary
    ) {
        override fun shouldRemove(): Boolean {
            return !MainApplication.isShowcaseMode()
        }
    },
    @JvmStatic
    NO_MAGISK(
        R.string.fail_magisk_missing,
        R.drawable.ic_baseline_numbers_24,
        View.OnClickListener { v: View ->
            IntentHelper.openUrl(
                v.context,
                "https://github.com/topjohnwu/Magisk/blob/master/docs/install.md"
            )
        }) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.getErrorNotification() !== this
        }
    },
    @JvmStatic
    NO_ROOT(R.string.fail_root_magisk, R.drawable.ic_baseline_numbers_24) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.getErrorNotification() !== this
        }
    },
    ROOT_DENIED(R.string.fail_root_denied, R.drawable.ic_baseline_numbers_24) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.getErrorNotification() !== this
        }
    },
    @JvmStatic
    MAGISK_OUTDATED(
        R.string.magisk_outdated,
        R.drawable.ic_baseline_update_24,
        View.OnClickListener { v: View ->
            IntentHelper.openUrl(
                v.context,
                "https://github.com/topjohnwu/Magisk/releases"
            )
        }) {
        override fun shouldRemove(): Boolean {
            return InstallerInitializer.peekMagiskPath() == null ||
                    InstallerInitializer.peekMagiskVersion() >=
                    Constants.MAGISK_VER_CODE_INSTALL_COMMAND
        }
    },
    @JvmStatic
    NO_INTERNET(R.string.fail_internet, R.drawable.ic_baseline_cloud_off_24) {
        override fun shouldRemove(): Boolean {
            return RepoManager.getINSTANCE().hasConnectivity()
        }
    },
    @JvmStatic
    REPO_UPDATE_FAILED(R.string.repo_update_failed, R.drawable.ic_baseline_cloud_off_24) {
        override fun shouldRemove(): Boolean {
            return RepoManager.getINSTANCE().isLastUpdateSuccess
        }
    },
    @JvmStatic
    NEED_CAPTCHA_ANDROIDACY(
        R.string.androidacy_need_captcha,
        R.drawable.ic_baseline_refresh_24,
        View.OnClickListener { v: View ->
            IntentHelper.openUrlAndroidacy(
                v.context,
                "https://" + Http.needCaptchaAndroidacyHost() + "/", false
            )
        }) {
        override fun shouldRemove(): Boolean {
            return (!RepoManager.isAndroidacyRepoEnabled()
                    || !Http.needCaptchaAndroidacy())
        }
    },
    @JvmStatic
    NO_WEB_VIEW(R.string.no_web_view, R.drawable.ic_baseline_android_24) {
        override fun shouldRemove(): Boolean {
            return Http.hasWebView()
        }
    },
    @JvmStatic
    UPDATE_AVAILABLE(
        R.string.app_update_available,
        R.drawable.ic_baseline_system_update_24,
        androidx.appcompat.R.attr.colorPrimary,
        com.google.android.material.R.attr.colorOnPrimary,
        View.OnClickListener { v: View ->
            IntentHelper.openUrl(
                v.context,
                "https://github.com/Androidacy/MagiskModuleManager/releases"
            )
        },
        false
    ) {
        override fun shouldRemove(): Boolean {
            return !AppUpdateManager.getAppUpdateManager().peekShouldUpdate()
        }
    },
    @JvmStatic
    INSTALL_FROM_STORAGE(
        R.string.install_from_storage,
        R.drawable.ic_baseline_storage_24,
        androidx.appcompat.R.attr.colorBackgroundFloating,
        com.google.android.material.R.attr.colorOnBackground,
        View.OnClickListener { v: View? ->
            val compatActivity = FoxActivity.getFoxActivity(v)
            val module = File(
                compatActivity.cacheDir,
                "installer" + File.separator + "module.zip"
            )
            IntentHelper.openFileTo(compatActivity, module) { d: File, u: Uri, s: Int ->
                if (s == IntentHelper.RESPONSE_FILE) {
                    try {
                        if (needPatch(d)) {
                            patchModuleSimple(
                                read(d),
                                FileOutputStream(d)
                            )
                        }
                        if (needPatch(d)) {
                            if (d.exists() && !d.delete()) Timber.w("Failed to delete non module zip")
                            Toast.makeText(
                                compatActivity,
                                R.string.invalid_format, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            IntentHelper.openInstaller(
                                compatActivity, d.absolutePath,
                                compatActivity.getString(
                                    R.string.local_install_title
                                ), null, null, false,
                                BuildConfig.DEBUG &&  // Use debug mode if no root
                                        InstallerInitializer.peekMagiskPath() == null
                            )
                        }
                    } catch (ignored: IOException) {
                        if (d.exists() && !d.delete()) Timber.w("Failed to delete invalid module")
                        Toast.makeText(
                            compatActivity,
                            R.string.invalid_format, Toast.LENGTH_SHORT
                        ).show()
                    }
                } else if (s == IntentHelper.RESPONSE_URL) {
                    IntentHelper.openInstaller(
                        compatActivity, u.toString(),
                        compatActivity.getString(
                            R.string.remote_install_title
                        ), null, null, false,
                        BuildConfig.DEBUG &&  // Use debug mode if no root
                                InstallerInitializer.peekMagiskPath() == null
                    )
                }
            }
        },
        false
    ) {
        override fun shouldRemove(): Boolean {
            return !BuildConfig.DEBUG &&
                    (MainApplication.isShowcaseMode() ||
                            InstallerInitializer.peekMagiskPath() == null)
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

    companion object {
        fun needPatch(target: File?): Boolean {
            try {
                ZipFile(target).use { zipFile ->
                    var validEntries = zipFile.getEntry("module.prop") != null
                    // ensure there's no anykernel.sh
                    validEntries = validEntries and (zipFile.getEntry("anykernel.sh") == null)
                    if (validEntries) {
                        // Ensure id of module is not empty and matches ^[a-zA-Z][a-zA-Z0-9._-]+$ regex
                        // We need to get the module.prop and parse the id= line
                        val moduleProp = zipFile.getEntry("module.prop")
                        // Parse the module.prop
                        if (moduleProp != null) {
                            // Find the line with id=, and check if it matches the regex
                            BufferedReader(InputStreamReader(zipFile.getInputStream(moduleProp))).use { reader ->
                                var line: String
                                while (reader.readLine().also { line = it } != null) {
                                    if (line.startsWith("id=")) {
                                        val id = line.substring(3)
                                        return id.isEmpty() || !id.matches(Regex("^[a-zA-Z][a-zA-Z0-9._-]+$"))
                                    }
                                }
                            }
                        } else {
                            return true
                        }
                    } else {
                        return true
                    }
                }
            } catch (e: IOException) {
                return true
            }
            return false
        }
    }
}