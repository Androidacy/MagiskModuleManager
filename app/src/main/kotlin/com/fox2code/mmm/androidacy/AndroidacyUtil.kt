/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("unused")

package com.fox2code.mmm.androidacy

import android.net.Uri
import com.fox2code.mmm.BuildConfig
import androidx.core.net.toUri

@Suppress("MemberVisibilityCanBePrivate", "MemberVisibilityCanBePrivate")
enum class AndroidacyUtil {
    ;

    companion object {
        const val REFERRER = "utm_source=AMMM&utm_medium=app"
        fun isAndroidacyLink(uri: Uri?): Boolean {
            return uri != null && isAndroidacyLink(uri.toString(), uri)
        }

        fun isAndroidacyLink(url: String?): Boolean {
            return url != null && isAndroidacyLink(url, Uri.parse(url))
        }

        fun isAndroidacyLink(url: String, uri: Uri): Boolean {
            var i = 0// Check both string and Uri to mitigate parse exploit
            return url.startsWith("https://") && url.indexOf("/", 8)
                .also { i = it } != -1 && url.substring(8, i)
                .endsWith("api.androidacy.com") && uri.host?.endsWith("api.androidacy.com") ?: false
        }

        fun isAndroidacyFileUrl(url: String?): Boolean {
            if (url == null) return false
            val uri = Uri.parse(url)
            return if (BuildConfig.DEBUG) {
                uri.host?.endsWith("api.androidacy.com") ?: false && (uri.path?.startsWith("/downloads") ?: false || uri.path?.startsWith(
                    "/magisk/file"
                ) ?: false || uri.path?.startsWith("/magisk/ddl") ?: false)
            } else {
                uri.host?.equals("production-api.androidacy.com") ?: false && (uri.path?.startsWith(
                    "/downloads"
                ) ?: false || uri.path?.startsWith("/magisk/file") ?: false || uri.path?.startsWith(
                    "/magisk/ddl"
                ) ?: false)
            }
        }

        // Avoid logging token
        @Suppress("NAME_SHADOWING")
        fun hideToken(url: String): String {
            // for token, device_id, and client_id, replace with <hidden> by using replaceAll to match until the next non-alphanumeric character or end
            // Also, URL decode
            var url = url
            url = Uri.decode(url)
            url = "$url&"
            url = url.replace("token=[^&]*".toRegex(), "token=<hidden>")
            url = url.replace("device_id=[^&]*".toRegex(), "device_id=<hidden>")
            url = url.replace("client_id=[^&]*".toRegex(), "client_id=<hidden>")
            // remove last & added at the end
            url = url.substring(0, url.length - 1)
            return url
        }

        fun getModuleId(moduleUrl: String): String? {
            // Get the &module= part
            val i = moduleUrl.indexOf("&module=")
            var moduleId: String
            // Match until next & or end
            if (i != -1) {
                val j = moduleUrl.indexOf('&', i + 1)
                moduleId = if (j == -1) {
                    moduleUrl.substring(i + 8)
                } else {
                    moduleUrl.substring(i + 8, j)
                }
                // URL decode
                moduleId = Uri.decode(moduleId)
                // Strip non alphanumeric
                moduleId = moduleId.replace("[^a-zA-Z\\d]".toRegex(), "")
                return moduleId
            }// fallback to last sergment minus .zip
            // Get the last segment
            val lastSegment = moduleUrl.toUri().lastPathSegment
            // Check if it ends with .zip
            if (lastSegment != null && lastSegment.endsWith(".zip")) {
                // Strip the .zip
                moduleId = lastSegment.substring(0, lastSegment.length - 4)
                // Strip non alphanumeric
                moduleId = moduleId.replace("[^a-zA-Z\\d]".toRegex(), "")
                return moduleId
            }
            return null
        }

        fun getModuleTitle(moduleUrl: String): String? {
            // Get the &title= part
            val i = moduleUrl.indexOf("&moduleTitle=")
            // Match until next & or end
            if (i != -1) {
                val j = moduleUrl.indexOf('&', i + 1)
                return if (j == -1) {
                    Uri.decode(moduleUrl.substring(i + 13))
                } else {
                    Uri.decode(moduleUrl.substring(i + 13, j))
                }
            }
            // fallback to last sergment minus .zip
            // Get the last segment
            val lastSegment = moduleUrl.toUri().lastPathSegment
            // Check if it ends with .zip
            if (lastSegment != null && lastSegment.endsWith(".zip")) {
                // Strip the .zip
                val moduleId = lastSegment.substring(0, lastSegment.length - 4)
                // Strip non alphanumeric
                val moduleTitle = moduleId.replace("[^a-zA-Z\\d]".toRegex(), "")
                return moduleTitle
            }
            return null
        }

        fun getChecksumFromURL(moduleUrl: String): String? {
            // Get the checksum query param
            val i = moduleUrl.indexOf("&checksum=")
            // Match until next & or end
            if (i != -1) {
                val j = moduleUrl.indexOf('&', i + 1)
                return if (j == -1) {
                    moduleUrl.substring(i + 10)
                } else {
                    moduleUrl.substring(i + 10, j)
                }
            }
            return null
        }
    }
}