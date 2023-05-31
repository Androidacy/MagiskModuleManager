@file:Suppress("unused")

package com.fox2code.mmm.androidacy

import android.net.Uri
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import java.io.IOException

enum class AndroidacyUtil {
    ;

    companion object {
        const val REFERRER = "utm_source=FoxMMM&utm_medium=app"
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

        @JvmStatic
        fun isAndroidacyFileUrl(url: String?): Boolean {
            if (url == null) return false
            for (prefix in arrayOf(
                "https://production-api.androidacy.com/downloads/",
                "https://production-api.androidacy.com/magisk/file/",
                "https://staging-api.androidacy.com/magisk/file/"
            )) { // Make both staging and non staging act the same
                if (url.startsWith(prefix)) return true
            }
            return false
        }

        // Avoid logging token
        @Suppress("NAME_SHADOWING")
        @JvmStatic
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

        @JvmStatic
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
            }
            require(!BuildConfig.DEBUG) { "Invalid module url: $moduleUrl" }
            return null
        }

        @JvmStatic
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
            return null
        }

        fun getChecksumFromURL(moduleUrl: String): String? {
            // Get the &version= part
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

        /**
         * Check if the url is a premium direct download link
         * @param url url to check
         * @return true if it is a premium direct download link
         * @noinspection unused
         */
        fun isPremiumDirectDownloadLink(url: String): Boolean {
            return url.contains("/magisk/ddl/")
        }

        /**
         * Returns the markdown directly from the API for rendering. Premium only, and internal testing only currently.
         * @param url URL to get markdown from
         * @return String of markdown
         * @noinspection unused
         */
        fun getMarkdownFromAPI(url: String?): String? {
            val md: ByteArray = try {
                doHttpGet(url!!, false)
            } catch (ignored: IOException) {
                return null
            }
            return String(md)
        }
    }
}