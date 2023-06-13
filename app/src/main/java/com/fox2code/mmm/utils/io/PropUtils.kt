/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("unused")

package com.fox2code.mmm.utils.io

import android.os.Build
import android.text.TextUtils
import com.fox2code.mmm.AppUpdateManager
import com.fox2code.mmm.manager.ModuleInfo
import com.topjohnwu.superuser.io.SuFileInputStream
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

enum class PropUtils {
    ;

    @Suppress("SpellCheckingInspection")
    companion object {
        private val moduleSupportsFallbacks = HashMap<String, String>()
        private val moduleConfigsFallbacks = HashMap<String, String>()
        private val moduleMinApiFallbacks = HashMap<String, Int>()
        private val moduleUpdateJsonFallbacks = HashMap<String, String>()
        private val moduleMTTRebornFallback = HashSet<String>()
        private val moduleImportantProp = HashSet(
            mutableListOf(
                "id", "name", "version", "versionCode"
            )
        )
        private var RIRU_MIN_API = 0

        // Note: These fallback values may not be up-to-date
        // They are only used if modules don't define the metadata
        init {
            // Support are pages or groups where the user can get support for the module
            moduleSupportsFallbacks["aospill"] = "https://t.me/PannekoX"
            moduleSupportsFallbacks["bromitewebview"] = "https://t.me/androidacy_discussions"
            moduleSupportsFallbacks["fontrevival"] = "https://t.me/androidacy_discussions"
            moduleSupportsFallbacks["MagiskHidePropsConf"] = "https://forum.xda-developers.com/t" +
                    "/module-magiskhide-props-config-safetynet-prop-edits-and-more-v6-1-1.3789228/"
            moduleSupportsFallbacks["quickstepswitcher"] = "https://t.me/QuickstepSwitcherSupport"
            moduleSupportsFallbacks["riru_edxposed"] = "https://t.me/EdXposed"
            moduleSupportsFallbacks["riru_lsposed"] = "https://github.com/LSPosed/LSPosed/issues"
            moduleSupportsFallbacks["substratum"] =
                "https://github.com/substratum/substratum/issues"
            // Config are application installed by modules that allow them to be configured
            moduleConfigsFallbacks["quickstepswitcher"] = "xyz.paphonb.quickstepswitcher"
            moduleConfigsFallbacks["hex_installer_module"] = "project.vivid.hex.bodhi"
            moduleConfigsFallbacks["riru_edxposed"] = "org.meowcat.edxposed.manager"
            moduleConfigsFallbacks["riru_lsposed"] = "org.lsposed.manager"
            moduleConfigsFallbacks["zygisk_lsposed"] = "org.lsposed.manager"
            moduleConfigsFallbacks["xposed_dalvik"] = "de.robv.android.xposed.installer"
            moduleConfigsFallbacks["xposed"] = "de.robv.android.xposed.installer"
            moduleConfigsFallbacks["substratum"] = "projekt.substratum"
            // minApi is the minimum android version required to use the module
            moduleMinApiFallbacks["HideNavBar"] = Build.VERSION_CODES.Q
            moduleMinApiFallbacks["riru_ifw_enhance"] = Build.VERSION_CODES.O
            moduleMinApiFallbacks["zygisk_ifw_enhance"] = Build.VERSION_CODES.O
            moduleMinApiFallbacks["riru_edxposed"] = Build.VERSION_CODES.O
            moduleMinApiFallbacks["zygisk_edxposed"] = Build.VERSION_CODES.O
            moduleMinApiFallbacks["riru_lsposed"] = Build.VERSION_CODES.O_MR1
            moduleMinApiFallbacks["zygisk_lsposed"] = Build.VERSION_CODES.O_MR1
            moduleMinApiFallbacks["noneDisplayCutout"] = Build.VERSION_CODES.P
            moduleMinApiFallbacks["quickstepswitcher"] = Build.VERSION_CODES.P
            moduleMinApiFallbacks["riru_clipboard_whitelist"] = Build.VERSION_CODES.Q
            // minApi for riru core include submodules
            moduleMinApiFallbacks["riru-core"] = Build.VERSION_CODES.M.also { RIRU_MIN_API = it }
            // Fallbacks in case updateJson is missing
            val ghUC = "https://raw.githubusercontent.com/"
            moduleUpdateJsonFallbacks["BluetoothLibraryPatcher"] =
                ghUC + "3arthur6/BluetoothLibraryPatcher/master/update.json"
            moduleUpdateJsonFallbacks["Detach"] =
                ghUC + "xerta555/Detach-Files/blob/master/Updater.json"
            for (module in arrayOf(
                "busybox-ndk", "adb-ndk", "twrp-keep",
                "adreno-dev", "nano-ndk", "zipsigner", "nexusmedia", "mtd-ndk"
            )) {
                moduleUpdateJsonFallbacks[module] =
                    ghUC + "Magisk-Modules-Repo/" + module + "/master/update.json"
            }
            moduleUpdateJsonFallbacks["riru_ifw_enhance"] = "https://github.com/" +
                    "Kr328/Riru-IFWEnhance/releases/latest/download/riru-ifw-enhance.json"
            moduleUpdateJsonFallbacks["zygisk_ifw_enhance"] = "https://github.com/" +
                    "Kr328/Riru-IFWEnhance/releases/latest/download/zygisk-ifw-enhance.json"
            moduleUpdateJsonFallbacks["riru_lsposed"] =
                "https://lsposed.github.io/LSPosed/release/riru.json"
            moduleUpdateJsonFallbacks["zygisk_lsposed"] =
                "https://lsposed.github.io/LSPosed/release/zygisk.json"
        }

        @Throws(IOException::class)
        fun readProperties(
            moduleInfo: ModuleInfo, file: String,
            local: Boolean
        ) {
            readProperties(moduleInfo, SuFileInputStream.open(file), file, local)
        }

        @Throws(IOException::class)
        fun readProperties(
            moduleInfo: ModuleInfo, file: String?,
            name: String, local: Boolean
        ) {
            readProperties(moduleInfo, SuFileInputStream.open(file!!), name, local)
        }

        @Throws(IOException::class)
        fun readProperties(
            moduleInfo: ModuleInfo, inputStream: InputStream?,
            name: String, local: Boolean
        ) {
            var readId = false
            var readIdSec = false
            var readName = false
            var readVersionCode = false
            var readVersion = false
            var readDescription = false
            var readUpdateJson = false
            var invalid = false
            var readMinApi = false
            var readMaxApi = false
            var readMMTReborn = false
            BufferedReader(
                InputStreamReader(inputStream, StandardCharsets.UTF_8)
            ).use { bufferedReader ->
                var line: String
                var lineNum = 0
                val iterator = bufferedReader.lineSequence().iterator()
                while (iterator.hasNext()) {
                    line = iterator.next()
                    if (lineNum == 0 && line.startsWith("\u0000")) {
                        while (line.startsWith("\u0000")) line = line.substring(1)
                    }
                    lineNum++
                    val index = line.indexOf('=')
                    if (index == -1 || line.startsWith("#")) continue
                    val key = line.substring(0, index)
                    val value = line.substring(index + 1).trim { it <= ' ' }
                    // check if field is defined on the moduleInfo object we are reading
                    if (moduleInfo.toString().contains(key)) {
                        continue
                    }
                    // name and id have their own implementation
                    if (isInvalidValue(key)) {
                        if (local) {
                            invalid = true
                            continue
                        } else throw IOException("Invalid key at line $lineNum")
                    } else {
                        if (value.isEmpty() && !moduleImportantProp.contains(key)) continue  // allow empty values to pass.
                        if (isInvalidValue(value)) {
                            if (local) {
                                invalid = true
                                continue
                            } else throw IOException("Invalid value for key $key")
                        }
                    }
                    when (key) {
                        "id" -> {
                            if (isInvalidValue(value)) {
                                if (local) {
                                    invalid = true
                                    break
                                }
                                throw IOException("Invalid module id!")
                            }
                            readId = true
                            if (moduleInfo.id != value) {
                                invalid = if (local) {
                                    true
                                } else {
                                    throw IOException(
                                        name + " has an non matching module id! " +
                                                "(Expected \"" + moduleInfo.id + "\" got \"" + value + "\""
                                    )
                                }
                            }
                        }

                        "name" -> {
                            if (readName) {
                                if (local) {
                                    invalid = true
                                    break
                                } else throw IOException("Duplicate module name!")
                            }
                            if (isInvalidValue(value)) {
                                if (local) {
                                    invalid = true
                                    break
                                }
                                throw IOException("Invalid module name!")
                            }
                            readName = true
                            moduleInfo.name = value
                            if (moduleInfo.id == value) {
                                readIdSec = true
                            }
                        }

                        "version" -> {
                            readVersion = true
                            moduleInfo.version = value
                        }

                        "versionCode" -> {
                            readVersionCode = true
                            try {
                                moduleInfo.versionCode = value.toLong()
                            } catch (e: RuntimeException) {
                                if (local) {
                                    invalid = true
                                    moduleInfo.versionCode = 0
                                } else throw e
                            }
                        }

                        "author" -> moduleInfo.author =
                            if (value.endsWith(" development team")) value.substring(
                                0,
                                value.length - 17
                            ) else value

                        "description" -> {
                            moduleInfo.description = value
                            readDescription = true
                        }

                        "updateJsonAk3" -> {
                            // Only allow AnyKernel3 helper to use "updateJsonAk3"
                            if ("ak3-helper" != moduleInfo.id) break
                            if (isInvalidURL(value)) break
                            moduleInfo.updateJson = value
                            readUpdateJson = true
                        }

                        "updateJson" -> {
                            if (isInvalidURL(value)) break
                            moduleInfo.updateJson = value
                            readUpdateJson = true
                        }

                        "changeBoot" -> moduleInfo.changeBoot =
                            java.lang.Boolean.parseBoolean(value)

                        "mmtReborn" -> {
                            moduleInfo.mmtReborn = java.lang.Boolean.parseBoolean(value)
                            readMMTReborn = true
                        }

                        "support" -> {
                            // Do not accept invalid or too broad support links
                            if (isInvalidURL(value) || "https://forum.xda-developers.com/" == value) break
                            moduleInfo.support = value
                        }

                        "donate" -> {
                            // Do not accept invalid donate links
                            if (isInvalidURL(value)) break
                            moduleInfo.donate = value
                        }

                        "config" -> moduleInfo.config = value
                        "needRamdisk" -> moduleInfo.needRamdisk =
                            java.lang.Boolean.parseBoolean(value)

                        "minMagisk" -> try {
                            val i = value.indexOf('.')
                            if (i == -1) {
                                moduleInfo.minMagisk = value.toInt()
                            } else {
                                moduleInfo.minMagisk =  // Allow 24.1 to mean 24100
                                    value.substring(0, i).toInt() * 1000 + value.substring(i + 1)
                                        .toInt() * 100
                            }
                        } catch (e: Exception) {
                            moduleInfo.minMagisk = 0
                        }

                        "minApi" -> {
                            // Special case for Riru EdXposed because
                            // minApi don't mean the same thing for them
                            if ("10" == value) break
                            try {
                                moduleInfo.minApi = value.toInt()
                                readMinApi = true
                            } catch (e: Exception) {
                                if (!readMinApi) moduleInfo.minApi = 0
                            }
                        }

                        "minSdkVersion" -> try {
                            moduleInfo.minApi = value.toInt()
                            readMinApi = true
                        } catch (e: Exception) {
                            if (!readMinApi) moduleInfo.minApi = 0
                        }

                        "maxSdkVersion", "maxApi" -> try {
                            moduleInfo.maxApi = value.toInt()
                            readMaxApi = true
                        } catch (e: Exception) {
                            if (!readMaxApi) moduleInfo.maxApi = 0
                        }
                    }
                }
            }
            if (!readId) {
                if (readIdSec && local) {
                    // Using the name for module id is not really appropriate, so beautify it a bit
                    moduleInfo.name = makeNameFromId(moduleInfo.id)
                } else if (!local) { // Allow local modules to not declare ids
                    throw IOException("Didn't read module id at least once!")
                }
            }
            if (!readVersionCode) {
                if (local) {
                    invalid = true
                    moduleInfo.versionCode = 0
                } else {
                    throw IOException("Didn't read module versionCode at least once!")
                }
            }
            if (!readName || isInvalidValue(moduleInfo.name)) {
                moduleInfo.name = makeNameFromId(moduleInfo.id)
            }
            if (!readVersion) {
                moduleInfo.version = "v" + moduleInfo.versionCode
            } else {
                moduleInfo.version = shortenVersionName(
                    moduleInfo.version, moduleInfo.versionCode
                )
            }
            if (!readDescription || isInvalidValue(moduleInfo.description)) {
                moduleInfo.description = ""
            }
            if (!readUpdateJson) {
                moduleInfo.updateJson = moduleUpdateJsonFallbacks[moduleInfo.id]
            }
            if (moduleInfo.minApi == 0 || !readMinApi) {
                val minApiFallback = moduleMinApiFallbacks[moduleInfo.id]
                if (minApiFallback != null) moduleInfo.minApi =
                    minApiFallback else if (moduleInfo.id.startsWith("riru_")
                    || moduleInfo.id.startsWith("riru-")
                ) moduleInfo.minApi = RIRU_MIN_API
            }
            if (moduleInfo.support == null) {
                moduleInfo.support = moduleSupportsFallbacks[moduleInfo.id]
            }
            if (moduleInfo.config == null) {
                moduleInfo.config = moduleConfigsFallbacks[moduleInfo.id]
            }
            if (!readMMTReborn) {
                moduleInfo.mmtReborn = moduleMTTRebornFallback.contains(moduleInfo.id) ||
                        AppUpdateManager.getFlagsForModule(moduleInfo.id) and
                        AppUpdateManager.FLAG_COMPAT_MMT_REBORN != 0
            }
            // All local modules should have an author
            // set to "Unknown" if author is missing.
            if (local && moduleInfo.author == null) {
                moduleInfo.author = "Unknown"
            }
            if (invalid) {
                moduleInfo.flags = moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
                // This shouldn't happen but just in case
                if (!local) throw IOException("Invalid properties!")
            }
        }

        @JvmStatic
        fun readModulePropSimple(inputStream: InputStream?, what: String): String? {
            if (inputStream == null) return null
            var moduleId: String? = null
            try {
                BufferedReader(
                    InputStreamReader(inputStream, StandardCharsets.UTF_8)
                ).use { bufferedReader ->
                    var line: String
                    while (bufferedReader.readLine().also { line = it } != null) {
                        while (line.startsWith("\u0000")) line = line.substring(1)
                        if (line.startsWith("$what=")) {
                            moduleId = line.substring(what.length + 1).trim { it <= ' ' }
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.i(e)
            }
            return moduleId
        }

        fun readModuleId(inputStream: InputStream?): String? {
            return readModulePropSimple(inputStream, "id")
        }

        @JvmStatic
        fun applyFallbacks(moduleInfo: ModuleInfo) {
            if (moduleInfo.support == null || moduleInfo.support!!.isEmpty()) {
                moduleInfo.support = moduleSupportsFallbacks[moduleInfo.id]
            }
            if (moduleInfo.config == null || moduleInfo.config!!.isEmpty()) {
                moduleInfo.config = moduleConfigsFallbacks[moduleInfo.id]
            }
            if (moduleInfo.minApi == 0) {
                val minApiFallback = moduleMinApiFallbacks[moduleInfo.id]
                if (minApiFallback != null) moduleInfo.minApi =
                    minApiFallback else if (moduleInfo.id.startsWith("riru_")
                    || moduleInfo.id.startsWith("riru-")
                ) moduleInfo.minApi = RIRU_MIN_API
            }
        }

        // Some module are really so low quality that it has become very annoying.
        @JvmStatic
        fun isLowQualityModule(moduleInfo: ModuleInfo?): Boolean {
            var description: String = moduleInfo?.description ?: return true
            return (moduleInfo.hasFlag(ModuleInfo.FLAG_METADATA_INVALID) || moduleInfo.name!!.length < 3 || moduleInfo.versionCode < 0 || moduleInfo.author == null || !TextUtils.isGraphic(
                moduleInfo.author
            ) || isNullString(moduleInfo.description.also {
                description = it!!
            }) || !TextUtils.isGraphic(description)) || description.lowercase() == moduleInfo.name!!.lowercase() || AppUpdateManager.getFlagsForModule(
                moduleInfo.id
            ) and AppUpdateManager.FLAG_COMPAT_LOW_QUALITY != 0 || moduleInfo.id.startsWith(".")
        }

        private fun isInvalidValue(name: String?): Boolean {
            return !TextUtils.isGraphic(name) || name!!.indexOf('\u0000') != -1
        }

        @JvmStatic
        fun isInvalidURL(url: String): Boolean {
            val i = url.indexOf('/', 8)
            val e = url.indexOf('.', 8)
            return i == -1 || e == -1 || e >= i || !url.startsWith("https://") || url.length <= 12 || url.indexOf(
                '\u0000'
            ) != -1
        }

        private fun makeNameFromId(moduleId: String): String {
            return moduleId.substring(0, 1).uppercase() +
                    moduleId.substring(1).replace('_', ' ')
        }

        @JvmStatic
        fun isNullString(string: String?): Boolean {
            return string.isNullOrEmpty() || "null" == string
        }

        // Make versionName no longer than 16 charters to avoid UI overflow.
        fun shortenVersionName(versionName: String?, versionCode: Long): String {
            if (isNullString(versionName)) return "v$versionCode"
            if (versionName!!.length <= 16) return versionName
            val i = versionName.lastIndexOf('.')
            return if (i != -1 && i <= 16 && versionName.indexOf('.') != i && versionName.indexOf(
                    ' '
                ) == -1
            ) versionName.substring(0, i) else "v$versionCode"
        }
    }
}