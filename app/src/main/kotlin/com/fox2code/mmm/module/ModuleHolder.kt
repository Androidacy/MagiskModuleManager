/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.module

import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.annotation.StringRes
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.formatTime
import com.fox2code.mmm.MainApplication.Companion.getSharedPreferences
import com.fox2code.mmm.MainApplication.Companion.isDisableLowQualityModuleFilter
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks.Companion.checkConfigTargetExists
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.utils.IntentHelper.Companion.getPackageOfConfig
import com.fox2code.mmm.utils.io.PropUtils.Companion.isLowQualityModule
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import timber.log.Timber
import java.util.Objects

@Suppress("unused", "KotlinConstantConditions", "RedundantSetter")
class ModuleHolder : Comparable<ModuleHolder?> {
    val moduleId: String
    val notificationType: NotificationType?
    val separator: Type?
    var footerPx: Int
    var onClickListener: View.OnClickListener? = null
    var moduleInfo: LocalModuleInfo? = null
    var repoModule: RepoModule? = null
    var filterLevel = 0

    constructor(moduleId: String) {
        this.moduleId = Objects.requireNonNull(moduleId)
        notificationType = null
        separator = null
        footerPx = -1
    }

    constructor(notificationType: NotificationType) {
        moduleId = ""
        this.notificationType = notificationType
        separator = null
        footerPx = -1
    }

    constructor(separator: Type?) {
        moduleId = ""
        notificationType = null
        this.separator = separator
        footerPx = -1
    }

    @Suppress("unused")
    constructor(footerPx: Int, header: Boolean) {
        moduleId = ""
        notificationType = null
        separator = null
        this.footerPx = footerPx
        filterLevel = if (header) 1 else 0
    }

    val isModuleHolder: Boolean
        get() = notificationType == null && separator == null && footerPx == -1
    val mainModuleInfo: ModuleInfo
        get() = if (repoModule != null && (moduleInfo == null || moduleInfo!!.versionCode < repoModule!!.moduleInfo.versionCode)) repoModule!!.moduleInfo else moduleInfo!!
    var updateZipUrl: String? = null
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.zipUrl else moduleInfo!!.updateZipUrl
        set
    val updateZipRepo: String?
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.repoData.preferenceId else "update_json"
    val updateZipChecksum: String?
        get() = if (moduleInfo == null || repoModule != null && moduleInfo!!.updateVersionCode < repoModule!!.moduleInfo.versionCode) repoModule!!.checksum else moduleInfo!!.updateChecksum
    val mainModuleName: String?
        get() {
            val moduleInfo = mainModuleInfo
            if (moduleInfo.name == null) throw Error("Error for ${type.name} id $moduleId")
            return moduleInfo.name
        }
    val mainModuleNameLowercase: String
        get() = mainModuleName!!.lowercase()
    val mainModuleConfig: String?
        get() {
            if (moduleInfo == null) return null
            var config = moduleInfo!!.config
            if (config == null && repoModule != null) {
                config = repoModule!!.moduleInfo.config
            }
            return config
        }
    val updateTimeText: String
        get() {
            if (repoModule == null) return ""
            val timeStamp = repoModule!!.lastUpdated
            return if (timeStamp <= 0) "" else formatTime(timeStamp)
        }
    val repoName: String?
        get() = if (repoModule == null) "" else repoModule!!.repoName

    fun hasFlag(flag: Int): Boolean {
        return moduleInfo != null && moduleInfo!!.hasFlag(flag)
    }

    val type: Type
        get() = if (footerPx != -1) {
            Type.FOOTER
        } else if (separator != null) {
            Type.SEPARATOR
        } else if (notificationType != null) {
            Type.NOTIFICATION
        } else if (moduleInfo == null && repoModule != null) {
            Type.INSTALLABLE
        } else if (moduleInfo!!.versionCode < moduleInfo!!.updateVersionCode || repoModule != null && moduleInfo!!.versionCode < repoModule!!.moduleInfo.versionCode) {
            Timber.i("Module %s is updateable", moduleId)
            var ignoreUpdate = false
            try {
                if (getSharedPreferences("mmm")?.getStringSet(
                        "pref_background_update_check_excludes",
                        HashSet()
                    )!!
                        .contains(
                            moduleInfo!!.id
                        )
                ) ignoreUpdate = true
            } catch (ignored: Exception) {
            }
            // now, we just had to make it more fucking complicated, didn't we?
            // we now have pref_background_update_check_excludes_version, which is a id:version stringset of versions the user may want to "skip"
            // oh, and because i hate myself, i made ^ at the beginning match that version and newer, and $ at the end match that version and older
            val stringSetT = getSharedPreferences("mmm")?.getStringSet(
                "pref_background_update_check_excludes_version",
                HashSet()
            )
            var version = ""
            Timber.d(stringSetT.toString())
            // unfortunately, stringset.contains() doesn't work for partial matches
            // so we have to iterate through the set
            for (s in stringSetT!!) {
                if (s.startsWith(moduleInfo!!.id)) {
                    version = s
                    Timber.d("igV: %s", version)
                    break
                }
            }
            var remoteVersionCode = moduleInfo!!.updateVersionCode.toString()
            if (repoModule != null) {
                remoteVersionCode = repoModule!!.moduleInfo.versionCode.toString()
            }
            if (version.isNotEmpty()) {
                // now, coerce everything into an int
                val remoteVersionCodeInt = remoteVersionCode.toInt()
                val wantsVersion = version.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()[1].replace("[^0-9]".toRegex(), "").toInt()
                // now find out if user wants up to and including this version, or this version and newer
                Timber.d("igV start with")
                version =
                    version.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[1]
                // this version and newer
                if (version.startsWith("^")) {
                    Timber.d("igV: newer")
                    // the wantsversion and newer
                    if (remoteVersionCodeInt >= wantsVersion) {
                        Timber.d("igV: skipping")
                        // if it is, we skip it
                        ignoreUpdate = true
                    }
                } else if (version.endsWith("$")) {
                    Timber.d("igV: older")
                    // this wantsversion and older
                    if (remoteVersionCodeInt <= wantsVersion) {
                        Timber.d("igV: skipping")
                        // if it is, we skip it
                        ignoreUpdate = true
                    }
                } else if (wantsVersion == remoteVersionCodeInt) {
                    Timber.d("igV: equal")
                    // if it is, we skip it
                    ignoreUpdate = true
                }
            }
            if (ignoreUpdate) {
                Timber.d("Module %s has update, but is ignored", moduleId)
                Type.INSTALLABLE
            } else {
                INSTANCE!!.modulesHaveUpdates = true
                if (!INSTANCE!!.updateModules.contains(moduleId)) {
                    INSTANCE!!.updateModules += moduleId
                    INSTANCE!!.updateModuleCount++
                }
                Timber.d(
                    "modulesHaveUpdates = %s, updateModuleCount = %s",
                    INSTANCE!!.modulesHaveUpdates,
                    INSTANCE!!.updateModuleCount
                )
                Type.UPDATABLE
            }
        } else {
            Type.INSTALLED
        }

    fun getCompareType(type: Type?): Type? {
        return separator
            ?: if (notificationType != null && notificationType.special) {
                Type.SPECIAL_NOTIFICATIONS
            } else {
                type
            }
    }

    fun shouldRemove(): Boolean {
        if (repoModule != null && moduleInfo != null && !hasUpdate()) {
            return true
        }
        return notificationType?.shouldRemove()
            ?: (footerPx == -1 && moduleInfo == null && (repoModule == null || !repoModule!!.repoData.isEnabled || isLowQualityModule(
                repoModule!!.moduleInfo
            ) && !isDisableLowQualityModuleFilter))
    }

    fun getButtons(
        context: Context?,
        buttonTypeList: MutableList<ActionButtonType?>,
        showcaseMode: Boolean
    ) {
        if (!isModuleHolder) return
        val localModuleInfo = moduleInfo
        // Add warning button if module id begins with a dot - this is a hidden module which could indicate malware
        if (moduleId.startsWith(".") || !moduleId.matches("^[a-zA-Z][a-zA-Z0-9._-]+$".toRegex())) {
            buttonTypeList.add(ActionButtonType.WARNING)
        }
        if (localModuleInfo != null && !showcaseMode) {
            buttonTypeList.add(ActionButtonType.UNINSTALL)
        }
        if (repoModule != null && repoModule!!.notesUrl != null) {
            buttonTypeList.add(ActionButtonType.INFO)
        }
        // in below case, module cannot be in both repo and local if version codes are the same (if same, add online button, otherwise add update button)
        if (repoModule != null || localModuleInfo?.updateZipUrl != null && localModuleInfo.updateVersionCode > localModuleInfo.versionCode) {
            buttonTypeList.add(ActionButtonType.UPDATE_INSTALL)
        }
        val rInfo = localModuleInfo?.remoteModuleInfo
        if (localModuleInfo != null && rInfo != null && rInfo.moduleInfo.versionCode <= localModuleInfo.versionCode || localModuleInfo != null && localModuleInfo.updateVersionCode != Long.MIN_VALUE && localModuleInfo.updateVersionCode <= localModuleInfo.versionCode) {
            buttonTypeList.add(ActionButtonType.REMOTE)
            // set updatezipurl on moduleholder

            if (localModuleInfo.updateZipUrl != null) {
                Timber.d("localModuleInfo: %s", localModuleInfo.updateZipUrl)
                updateZipUrl = localModuleInfo.updateZipUrl
            }
            if (repoModule != null) {
                Timber.d("repoModule: %s", repoModule!!.zipUrl)
                updateZipUrl = repoModule!!.zipUrl
            }
            // last ditch effort, try to get remoteModuleInfo from localModuleInfo
            if (rInfo != null) {
                Timber.d("remoteModuleInfo: %s", rInfo.zipUrl)
                updateZipUrl = rInfo.zipUrl
                moduleInfo?.updateZipUrl = rInfo.zipUrl
            }
        }
        val config = mainModuleConfig
        if (config != null) {
            if (config.startsWith("https://www.androidacy.com/") && hasWebView()) {
                buttonTypeList.add(ActionButtonType.CONFIG)
            } else {
                val pkg = getPackageOfConfig(config)
                try {
                    checkConfigTargetExists(context!!, pkg, config)
                    buttonTypeList.add(ActionButtonType.CONFIG)
                } catch (e: PackageManager.NameNotFoundException) {
                    Timber.w("Config package \"$pkg\" missing for module \"$moduleId\"")
                }
            }
        }
        var moduleInfo: ModuleInfo? = mainModuleInfo
        if (moduleInfo == null) { // Avoid concurrency NPE
            if (localModuleInfo == null) return
            moduleInfo = localModuleInfo
        }
        if (moduleInfo.support != null) {
            buttonTypeList.add(ActionButtonType.SUPPORT)
        }
        if (moduleInfo.donate != null) {
            buttonTypeList.add(ActionButtonType.DONATE)
        }
        if (moduleInfo.safe) {
            buttonTypeList.add(ActionButtonType.SAFE)
        }
    }

    fun hasUpdate(): Boolean {
        return moduleInfo != null && repoModule != null && moduleInfo!!.versionCode < repoModule!!.moduleInfo.versionCode
    }

    override operator fun compareTo(other: ModuleHolder?): Int {
        // Compare depend on type, also allow type spoofing
        val selfTypeReal = type
        val otherTypeReal = other!!.type
        val selfType = getCompareType(selfTypeReal)
        val otherType = other.getCompareType(otherTypeReal)
        val compare = selfType!!.compareTo(otherType!!)
        return if (compare != 0) compare else if (selfTypeReal === otherTypeReal) selfTypeReal.compare(
            this,
            other
        ) else selfTypeReal.compareTo(otherTypeReal)
    }

    override fun toString(): String {
        return "ModuleHolder{moduleId='$moduleId', notificationType=$notificationType, separator=$separator, footerPx=$footerPx}"
    }

    enum class Type(
        @field:StringRes @param:StringRes val title: Int,
        val hasBackground: Boolean,
        val moduleHolder: Boolean
    ) : Comparator<ModuleHolder?> {
        HEADER(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.separator!!.compareTo(o2.separator!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        SEPARATOR(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.separator!!.compareTo(o2.separator!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        NOTIFICATION(R.string.loading, true, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.notificationType!!.compareTo(o2.notificationType!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        UPDATABLE(R.string.updatable, true, true) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        INSTALLED(R.string.installed, true, true) {
            // get stacktrace for debugging
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    val cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    return if (cmp != 0) cmp else o1.mainModuleNameLowercase.compareTo(o2.mainModuleNameLowercase)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        SPECIAL_NOTIFICATIONS(R.string.loading, true, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        INSTALLABLE(
            R.string.online_repo,
            true,
            true
        ) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    var cmp = o1.filterLevel.compareTo(o2.filterLevel)
                    if (cmp != 0) return cmp
                    val lastUpdated1 =
                        if (o1.repoModule == null) 0L else o1.repoModule!!.lastUpdated
                    val lastUpdated2 =
                        if (o2.repoModule == null) 0L else o2.repoModule!!.lastUpdated
                    cmp = lastUpdated2.compareTo(lastUpdated1)
                    return if (cmp != 0) cmp else o1.mainModuleName!!.compareTo(o2.mainModuleName!!)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        },
        FOOTER(R.string.loading, false, false) {
            override fun compare(o1: ModuleHolder?, o2: ModuleHolder?): Int {
                if (o1 != null && o2 != null) {
                    return o1.footerPx.compareTo(o2.footerPx)
                } else if (o1 != null) {
                    return -1
                } else if (o2 != null) {
                    return 1
                }
                return 0
            }
        };
    }
}
