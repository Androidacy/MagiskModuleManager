/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.module

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.net.Uri
import android.text.Html
import android.text.Spanned
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.isShowcaseMode
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyLink
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.IntentHelper.Companion.openConfig
import com.fox2code.mmm.utils.IntentHelper.Companion.openInstaller
import com.fox2code.mmm.utils.IntentHelper.Companion.openMarkdown
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrl
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrlAndroidacy
import com.fox2code.mmm.utils.IntentHelper.Companion.startDownloadUsingDownloadManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import ly.count.android.sdk.Countly
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
            // if analytics is enabled, track the event
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_description", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
            val notesUrl = moduleHolder.repoModule?.notesUrl
            if (isAndroidacyLink(notesUrl)) {
                try {
                    openUrlAndroidacy(
                        button.context,
                        notesUrl,
                        false,
                        moduleHolder.repoModule?.moduleInfo?.name,
                        moduleHolder.mainModuleConfig
                    )
                } catch (e: Exception) {
                    Timber.e(e)
                    Timber.e("Error opening notes - androidacy link. This should never happen.")
                    Toast.makeText(
                        button.context,
                        R.string.error_opening_notes,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                try {
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
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(
                        button.context,
                        R.string.error_opening_notes,
                        Toast.LENGTH_SHORT
                    ).show()
                }
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
            if (MainApplication.getPreferences("mmm")
                    ?.getBoolean("pref_require_security", false) == true
            ) {
                // get safe status from either mainmoduleinfo or repo module
                val safe =
                    moduleHolder.mainModuleInfo.safe || moduleHolder.repoModule?.moduleInfo?.safe ?: false
                if (!safe) {
                    // block local install for safety
                    MaterialAlertDialogBuilder(button.context)
                        .setTitle(R.string.install_blocked)
                        .setMessage(R.string.install_blocked_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                    return
                }
            }
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
            // send event to countly
            if (MainApplication.analyticsAllowed()) Countly.sharedInstance().events()
                .recordEvent("view_update_install", HashMap<String, Any>().apply {
                    put("module", name ?: "null")
                })
            // prefer repomodule if possible
            var updateZipUrl = ""
            if (moduleHolder.repoModule != null && moduleHolder.repoModule!!.zipUrl != null) {
                updateZipUrl = moduleHolder.repoModule!!.zipUrl!!
            }
            // if repomodule is null, try localmoduleinfo
            if (updateZipUrl.isEmpty() && moduleHolder.moduleInfo != null && moduleHolder.moduleInfo!!.updateZipUrl != null) {
                updateZipUrl = moduleHolder.moduleInfo!!.updateZipUrl!!
            }
            // still empty? show dialog
            if (updateZipUrl.isEmpty()) {
                val materialAlertDialogBuilder = MaterialAlertDialogBuilder(button.context)
                materialAlertDialogBuilder.setTitle(R.string.invalid_update_url)
                materialAlertDialogBuilder.setMessage(R.string.invalid_update_url_message)
                materialAlertDialogBuilder.setPositiveButton(android.R.string.ok, null)
                materialAlertDialogBuilder.setIcon(R.drawable.ic_baseline_error_24)
                materialAlertDialogBuilder.show()
                return
            }
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
                markwon = INSTANCE!!.markwon
                // Re-render each time in cse of config changes
                desc = markwon!!.toMarkdown(localModuleInfo.updateChangeLog)
            }
            if (desc.isNullOrEmpty()) {
                builder.setMessage(R.string.no_desc_found)
            } else {
                builder.setMessage(desc)
            }
            if (MainApplication.forceDebugLogging) Timber.i("URL: %s", updateZipUrl)
            builder.setNegativeButton(R.string.download_module) { d: DialogInterface?, _: Int ->
                startDownloadUsingDownloadManager(
                    button.context,
                    updateZipUrl,
                    moduleInfo.name,
                )
                // close the dialog and finish
                d?.cancel()
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
            val dim5dp = INSTANCE!!.lastActivity?.resources!!.getDimensionPixelSize(
                R.dimen.dim5dp
            )
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
            // if analytics is enabled, track the event
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_uninstall", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
            if (MainApplication.forceDebugLogging) Timber.i(
                Integer.toHexString(
                    moduleHolder.moduleInfo?.flags ?: 0
                )
            )
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
                    INSTANCE!!.lastActivity!!
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
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_config", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
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
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_support", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
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
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_donate", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
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
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_warning", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
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
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_safe", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
            MaterialAlertDialogBuilder(button.context).setTitle(R.string.safe_module)
                .setMessage(R.string.safe_message).setPositiveButton(
                    R.string.understand
                ) { _: DialogInterface?, _: Int -> }
                .create().show()
        }
    },
    REMOTE {
        @Suppress("NAME_SHADOWING")
        override fun doAction(button: Chip, moduleHolder: ModuleHolder) {
            if (MainApplication.forceDebugLogging) Timber.d(
                "doAction: remote module for %s",
                moduleHolder.moduleInfo?.name ?: "null"
            )
            // that module is from remote repo
            val name: String? = if (moduleHolder.moduleInfo != null) {
                moduleHolder.moduleInfo!!.name
            } else {
                moduleHolder.repoModule?.moduleInfo?.name
            }
            // positive button executes install logic and says reinstall. negative button does nothing
            if (MainApplication.analyticsAllowed()) {
                Countly.sharedInstance().events()
                    .recordEvent("view_update_install", HashMap<String, Any>().apply {
                        put("module", name ?: "null")
                    })
            }
            val madb = MaterialAlertDialogBuilder(button.context)
            madb.setTitle(R.string.remote_module)
            val moduleInfo: ModuleInfo = if (moduleHolder.mainModuleInfo != null) {
                moduleHolder.mainModuleInfo
            } else {
                moduleHolder.repoModule?.moduleInfo ?: return
            }
            var updateZipUrl = moduleHolder.updateZipUrl
            if (updateZipUrl.isNullOrEmpty()) {
                // try repoModule.zipUrl
                val repoModule = moduleHolder.repoModule
                if (repoModule?.zipUrl.isNullOrEmpty()) {
                    Timber.e("No repo update zip url for %s", moduleInfo.name)
                } else {
                    updateZipUrl = repoModule?.zipUrl
                }
                // next try localModuleInfo.updateZipUrl
                if (updateZipUrl.isNullOrEmpty()) {
                    val localModuleInfo = moduleHolder.moduleInfo
                    if (localModuleInfo != null) {
                        updateZipUrl = localModuleInfo.updateZipUrl
                    } else {
                        Timber.e("No local update zip url for %s", moduleInfo.name)
                    }
                }
                if (updateZipUrl.isNullOrEmpty()) {
                    Timber.e("No update zip url at all for %s", moduleInfo.name)
                    // last ditch effort try moduleinfo
                    updateZipUrl = moduleHolder.moduleInfo?.updateZipUrl
                }
                // LAST LAST ditch effort try localModuleInfo.remoteModuleInfo.zipUrl
                if (updateZipUrl.isNullOrEmpty()) {
                    val localModuleInfo = moduleHolder.moduleInfo
                    if (localModuleInfo != null) {
                        updateZipUrl = localModuleInfo.remoteModuleInfo?.zipUrl
                    } else {
                        Timber.e("No local update zip url for %s", moduleInfo.name)
                    }
                }
            }
            if (!updateZipUrl.isNullOrEmpty()) {
                madb.setMessage(
                    Html.fromHtml(
                        button.context.getString(
                            R.string.remote_message,
                            name
                        ), Html.FROM_HTML_MODE_COMPACT
                    )
                )
                madb.setPositiveButton(
                    R.string.reinstall
                ) { _: DialogInterface?, _: Int ->
                    if (MainApplication.forceDebugLogging) Timber.d(
                        "Set moduleinfo to %s",
                        moduleInfo.name
                    )
                    val name: String? = if (moduleHolder.moduleInfo != null) {
                        moduleHolder.moduleInfo!!.name
                    } else {
                        moduleHolder.repoModule?.moduleInfo?.name
                    }
                    if (MainApplication.forceDebugLogging) Timber.d(
                        "doAction: remote module for %s",
                        name
                    )
                    if (MainApplication.analyticsAllowed()) {
                        Countly.sharedInstance().events()
                            .recordEvent("view_update_install", HashMap<String, Any>().apply {
                                put("module", name ?: "null")
                            })
                    }
                    // Androidacy manage the selection between download and install
                    if (isAndroidacyLink(updateZipUrl)) {
                        if (MainApplication.forceDebugLogging) Timber.d("Androidacy link detected")
                        openUrlAndroidacy(
                            button.context,
                            updateZipUrl,
                            true,
                            moduleInfo.name,
                            moduleInfo.config
                        )
                        return@setPositiveButton
                    }
                    val hasRoot = peekMagiskPath() != null && !isShowcaseMode
                    val builder = MaterialAlertDialogBuilder(button.context)
                    builder.setTitle(moduleInfo.name).setCancelable(true)
                        .setIcon(R.drawable.ic_baseline_extension_24)
                    var desc: CharSequence? = moduleInfo.description
                    var markwon: Markwon? = null
                    val localModuleInfo = moduleHolder.moduleInfo
                    if (localModuleInfo != null && localModuleInfo.updateChangeLog.isNotEmpty()) {
                        markwon = INSTANCE!!.markwon
                        // Re-render each time in cse of config changes
                        desc = markwon!!.toMarkdown(localModuleInfo.updateChangeLog)
                    }
                    if (desc.isNullOrEmpty()) {
                        builder.setMessage(R.string.no_desc_found)
                    } else {
                        builder.setMessage(desc)
                    }
                    if (MainApplication.forceDebugLogging) Timber.i("URL: %s", updateZipUrl)
                    builder.setNegativeButton(R.string.download_module) { d: DialogInterface?, _: Int ->
                        startDownloadUsingDownloadManager(
                            button.context,
                            updateZipUrl,
                            moduleInfo.name,
                        )
                        d?.cancel()
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
                    val dim5dp = INSTANCE!!.lastActivity?.resources!!.getDimensionPixelSize(
                        R.dimen.dim5dp
                    )
                    builder.setBackgroundInsetStart(dim5dp).setBackgroundInsetEnd(dim5dp)
                    val alertDialog = builder.show()
                    for (i in -3..-1) {
                        val alertButton = alertDialog.getButton(i)
                        if (alertButton != null && alertButton.paddingStart > dim5dp) {
                            alertButton.setPadding(dim5dp, dim5dp, dim5dp, dim5dp)
                        }
                    }
                    if (markwon != null) {
                        val messageView =
                            alertDialog.window!!.findViewById<TextView>(android.R.id.message)
                        markwon.setParsedMarkdown(messageView, (desc as Spanned?)!!)
                    }
                }
            } else {
                madb.setMessage(button.context.getString(R.string.remote_message_no_update, name))
            }
            madb.setNegativeButton(R.string.cancel, null)
            madb.create().show()
        }

        override fun update(button: Chip, moduleHolder: ModuleHolder) {
            val icon: Int = R.drawable.baseline_cloud_download_24
            button.chipIcon = button.context.getDrawable(icon)
            button.setText(R.string.online)
        }
    };

    @DrawableRes
    private val iconId: Int

    constructor() {
        iconId = 0
    }

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