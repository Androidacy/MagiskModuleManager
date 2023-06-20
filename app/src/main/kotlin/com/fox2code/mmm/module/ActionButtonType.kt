/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.module

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.net.Uri
import android.text.Spanned
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.isShowcaseMode
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyLink
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.IntentHelper.Companion.openConfig
import com.fox2code.mmm.utils.IntentHelper.Companion.openCustomTab
import com.fox2code.mmm.utils.IntentHelper.Companion.openInstaller
import com.fox2code.mmm.utils.IntentHelper.Companion.openMarkdown
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrl
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrlAndroidacy
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.util.Objects

@Suppress("SENSELESS_COMPARISON")
@SuppressLint("UseCompatLoadingForDrawables")
enum class ActionButtonType {
    INFO {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            button.chipIcon = button.context.getDrawable(R.drawable.ic_baseline_info_24)
            button.setText(R.string.description)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("view_notes", name).with(INSTANCE!!.getTracker())
            val notesUrl = moduleHolder.repoModule?.notesUrl
            if (isAndroidacyLink(notesUrl)) {
                openUrlAndroidacy(
                    button.context,
                    notesUrl,
                    false,
                    moduleHolder.repoModule?.moduleInfo?.name,
                    moduleHolder.mainModuleConfig
                )
            } else {
                openMarkdown(
                    button.context,
                    notesUrl,
                    moduleHolder.repoModule?.moduleInfo?.name,
                    moduleHolder.mainModuleConfig,
                    moduleHolder.repoModule?.moduleInfo?.changeBoot,
                    moduleHolder.repoModule?.moduleInfo?.needRamdisk,
                    moduleHolder.repoModule?.moduleInfo?.minMagisk ?: 0,
                    moduleHolder.repoModule?.moduleInfo?.minApi ?: 0,
                    moduleHolder.repoModule?.moduleInfo?.maxApi ?: 9999
                )
            }
        }

        override fun doActionLong(button: Chip, moduleHolder: ModuleHolder): Boolean {
            val context = button.context
            Toast.makeText(
                context,
                context.getString(R.string.module_id_prefix) + moduleHolder.moduleId,
                Toast.LENGTH_SHORT
            ).show()
            return true
        }
    },
    UPDATE_INSTALL {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            val icon: Int
            if (moduleHolder.hasUpdate()) {
                icon = R.drawable.ic_baseline_update_24
                button.setText(R.string.update)
            } else if (moduleHolder.moduleInfo != null) {
                icon = R.drawable.ic_baseline_refresh_24
                button.setText(R.string.reinstall)
            } else {
                icon = R.drawable.ic_baseline_system_update_24
                button.setText(R.string.install)
            }
            button.chipIcon = button.context.getDrawable(icon)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            // if mainmoduleinfo is null, we are in repo mode
            val moduleInfo: ModuleInfo = if (moduleHolder.mainModuleInfo != null) {
                moduleHolder.mainModuleInfo
            } else {
                moduleHolder.repoModule?.moduleInfo ?: return
            }
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("view_update_install", name).with(INSTANCE!!.getTracker())
            // if text is reinstall, we need to uninstall first - warn the user but don't proceed
            if (moduleHolder.moduleInfo != null) {
                // get the text
                val text = button.text
                // if the text is reinstall, warn the user
                if (text == button.context.getString(R.string.reinstall)) {
                    val builder = MaterialAlertDialogBuilder(button.context)
                    builder.setTitle(R.string.reinstall)
                        .setMessage(R.string.reinstall_warning)
                        .setCancelable(true)
                        // ok button that does nothing
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return
                }
            }
            val updateZipUrl = moduleHolder.updateZipUrl ?: return
            // Androidacy manage the selection between download and install
            if (isAndroidacyLink(updateZipUrl)) {
                openUrlAndroidacy(
                    button.context,
                    updateZipUrl,
                    true,
                    moduleInfo.name,
                    moduleInfo.config
                )
                return
            }
            val hasRoot = peekMagiskPath() != null && !isShowcaseMode
            val builder = MaterialAlertDialogBuilder(button.context)
            builder.setTitle(moduleInfo.name).setCancelable(true)
                .setIcon(R.drawable.ic_baseline_extension_24)
            var desc: CharSequence? = moduleInfo.description
            var markwon: Markwon? = null
            val localModuleInfo = moduleHolder.moduleInfo
            if (localModuleInfo != null && localModuleInfo.updateChangeLog.isNotEmpty()) {
                markwon = INSTANCE!!.getMarkwon()
                // Re-render each time in cse of config changes
                desc = markwon!!.toMarkdown(localModuleInfo.updateChangeLog)
            }
            if (desc.isNullOrEmpty()) {
                builder.setMessage(R.string.no_desc_found)
            } else {
                builder.setMessage(desc)
            }
            Timber.i("URL: %s", updateZipUrl)
            builder.setNegativeButton(R.string.download_module) { _: DialogInterface?, _: Int ->
                openCustomTab(
                    button.context,
                    updateZipUrl
                )
            }
            if (hasRoot) {
                builder.setPositiveButton(if (moduleHolder.hasUpdate()) R.string.update_module else R.string.install_module) { _: DialogInterface?, _: Int ->
                    val updateZipChecksum = moduleHolder.updateZipChecksum
                    openInstaller(
                        button.context,
                        updateZipUrl,
                        moduleInfo.name,
                        moduleInfo.config,
                        updateZipChecksum,
                        moduleInfo.mmtReborn
                    )
                }
            }
            ExternalHelper.INSTANCE.injectButton(
                builder,
                { Uri.parse(updateZipUrl) },
                moduleHolder.updateZipRepo
            )
            val dim5dp = FoxDisplay.dpToPixel(5f)
            builder.setBackgroundInsetStart(dim5dp).setBackgroundInsetEnd(dim5dp)
            val alertDialog = builder.show()
            for (i in -3..-1) {
                val alertButton = alertDialog.getButton(i)
                if (alertButton != null && alertButton.paddingStart > dim5dp) {
                    alertButton.setPadding(dim5dp, dim5dp, dim5dp, dim5dp)
                }
            }
            if (markwon != null) {
                val messageView = alertDialog.window!!.findViewById<TextView>(android.R.id.message)
                markwon.setParsedMarkdown(messageView, (desc as Spanned?)!!)
            }
        }
    },
    UNINSTALL {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            val icon =
                if (moduleHolder.hasFlag(ModuleInfo.FLAG_MODULE_UNINSTALLING)) R.drawable.ic_baseline_delete_outline_24 else if (!moduleHolder.hasFlag(
                        ModuleInfo.FLAG_MODULE_UPDATING
                    ) || moduleHolder.hasFlag(ModuleInfo.FLAGS_MODULE_ACTIVE)
                ) R.drawable.ic_baseline_delete_24 else R.drawable.ic_baseline_delete_forever_24
            button.chipIcon = button.context.getDrawable(icon)
            button.setText(R.string.uninstall)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            if (!moduleHolder.hasFlag(ModuleInfo.FLAGS_MODULE_ACTIVE or ModuleInfo.FLAG_MODULE_UNINSTALLING) && moduleHolder.hasFlag(
                    ModuleInfo.FLAG_MODULE_UPDATING
                )
            ) {
                doActionLong(button, moduleHolder)
                return
            }
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("uninstall_module", name).with(INSTANCE!!.getTracker())
            Timber.i(Integer.toHexString(moduleHolder.moduleInfo?.flags ?: 0))
            if (!instance!!.setUninstallState(
                    moduleHolder.moduleInfo!!, !moduleHolder.hasFlag(
                        ModuleInfo.FLAG_MODULE_UNINSTALLING
                    )
                )
            ) {
                Timber.e("Failed to switch uninstalled state!")
            }
            update(button, moduleHolder)
        }

        override fun doActionLong(button: Chip, moduleHolder: ModuleHolder): Boolean {
            if (moduleHolder.moduleInfo == null) {
                return false
            }
            // Actually a module having mount is the only issue when deleting module
            if (moduleHolder.moduleInfo!!.hasFlag(ModuleInfo.FLAG_MODULE_HAS_ACTIVE_MOUNT)) return false // We can't trust active flag on first boot
            val builder = MaterialAlertDialogBuilder(button.context)
            builder.setTitle(R.string.master_delete)
            builder.setPositiveButton(R.string.master_delete_yes) { _: DialogInterface?, _: Int ->
                val moduleId = moduleHolder.moduleInfo!!.id
                if (!instance!!.masterClear(moduleHolder.moduleInfo!!)) {
                    Toast.makeText(button.context, R.string.master_delete_fail, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    moduleHolder.moduleInfo = null
                    FoxActivity.getFoxActivity(button).refreshUI()
                    Timber.e("Cleared: %s", moduleId)
                }
            }
            builder.setNegativeButton(R.string.master_delete_no) { _: DialogInterface?, _: Int -> }
            builder.create()
            builder.show()
            return true
        }
    },
    CONFIG {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            button.chipIcon =
                button.context.getDrawable(R.drawable.ic_baseline_app_settings_alt_24)
            button.setText(R.string.config)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            if (moduleHolder.moduleInfo == null) {
                return
            }
            val config = moduleHolder.mainModuleConfig ?: return
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("config_module", name).with(INSTANCE!!.getTracker())
            if (isAndroidacyLink(config)) {
                openUrlAndroidacy(button.context, config, true)
            } else {
                openConfig(button.context, config)
            }
        }
    },
    SUPPORT {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            val moduleInfo = moduleHolder.mainModuleInfo
            button.chipIcon = button.context.getDrawable(
                supportIconForUrl(moduleInfo.support)
            )
            button.setText(R.string.support)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("support_module", name).with(INSTANCE!!.getTracker())
            openUrl(button.context, Objects.requireNonNull(moduleHolder.mainModuleInfo.support))
        }
    },
    DONATE {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            val moduleInfo = moduleHolder.mainModuleInfo
            button.chipIcon = button.context.getDrawable(
                donateIconForUrl(moduleInfo.donate)
            )
            button.setText(R.string.donate)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("donate_module", name).with(INSTANCE!!.getTracker())
            openUrl(button.context, moduleHolder.mainModuleInfo.donate)
        }
    },
    WARNING {
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            button.chipIcon = button.context.getDrawable(R.drawable.ic_baseline_warning_24)
            button.setText(R.string.warning)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("warning_module", name).with(INSTANCE!!.getTracker())
            MaterialAlertDialogBuilder(button.context).setTitle(R.string.warning)
                .setMessage(R.string.warning_message).setPositiveButton(
                R.string.understand
            ) { _: DialogInterface?, _: Int -> }
                .create().show()
        }
    },
    SAFE {
        // SAFE is for modules that the api says are clean. only supported by androidacy currently
        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            button.chipIcon =
                button.context.getDrawable(R.drawable.baseline_verified_user_24)
            button.setText(R.string.safe)
        }

        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            TrackHelper.track().event("safe_module", name).with(INSTANCE!!.getTracker())
            MaterialAlertDialogBuilder(button.context).setTitle(R.string.safe_module)
                .setMessage(R.string.safe_message).setPositiveButton(
                R.string.understand
            ) { _: DialogInterface?, _: Int -> }
                .create().show()
        }
    };

    @DrawableRes
    private val iconId: Int

    constructor() {
        iconId = 0
    }

    @Suppress("unused")
    constructor(iconId: Int) {
        this.iconId = iconId
    }

    open fun update(button: Chip, moduleHolder: ModuleHolder) {
        button.chipIcon = button.context.getDrawable(iconId)
    }

    abstract fun doAction(button: Chip, moduleHolder: ModuleHolder)
    open fun doActionLong(button: Chip, moduleHolder: ModuleHolder): Boolean {
        return false
    }

    companion object {
        @JvmStatic
        @DrawableRes
        fun supportIconForUrl(url: String?): Int {
            var icon = R.drawable.ic_baseline_support_24
            if (url == null) {
                return icon
            } else if (url.startsWith("https://t.me/")) {
                icon = R.drawable.ic_baseline_telegram_24
            } else if (url.startsWith("https://discord.gg/") || url.startsWith("https://discord.com/invite/")) {
                icon = R.drawable.ic_baseline_discord_24
            } else if (url.startsWith("https://github.com/")) {
                icon = R.drawable.ic_github
            } else if (url.startsWith("https://gitlab.com/")) {
                icon = R.drawable.ic_gitlab
            } else if (url.startsWith("https://forum.xda-developers.com/")) {
                icon = R.drawable.ic_xda
            }
            return icon
        }

        @JvmStatic
        @DrawableRes
        fun donateIconForUrl(url: String?): Int {
            var icon = R.drawable.ic_baseline_monetization_on_24
            if (url == null) {
                return icon
            } else if (url.startsWith("https://www.paypal.me/") || url.startsWith("https://www.paypal.com/paypalme/") || url.startsWith(
                    "https://www.paypal.com/donate/"
                )
            ) {
                icon = R.drawable.ic_baseline_paypal_24
            } else if (url.startsWith("https://patreon.com/") || url.startsWith("https://www.patreon.com/")) {
                icon = R.drawable.ic_patreon
            }
            return icon
        }
    }
}