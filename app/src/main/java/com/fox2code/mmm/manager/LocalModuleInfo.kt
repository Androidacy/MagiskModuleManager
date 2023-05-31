package com.fox2code.mmm.manager

import com.fox2code.mmm.markdown.MarkdownUrlLinker.Companion.urlLinkify
import com.fox2code.mmm.utils.FastException
import com.fox2code.mmm.utils.io.PropUtils
import com.fox2code.mmm.utils.io.net.Http
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.nio.charset.StandardCharsets

class LocalModuleInfo(id: String?) : ModuleInfo(id!!) {
    @JvmField
    var updateVersion: String? = null
    @JvmField
    var updateVersionCode = Long.MIN_VALUE
    @JvmField
    var updateZipUrl: String? = null
    private var updateChangeLogUrl: String? = null
    @JvmField
    var updateChangeLog = ""
    @JvmField
    var updateChecksum: String? = null
    fun checkModuleUpdate() {
        if (updateJson != null && flags and FLAG_MM_REMOTE_MODULE == 0) {
            try {
                val jsonUpdate = JSONObject(
                    String(
                        Http.doHttpGet(
                            updateJson!!, true
                        ), StandardCharsets.UTF_8
                    )
                )
                updateVersion = jsonUpdate.optString("version")
                updateVersionCode = jsonUpdate.getLong("versionCode")
                updateZipUrl = jsonUpdate.getString("zipUrl")
                updateChangeLogUrl = jsonUpdate.optString("changelog")
                try {
                    var desc = String(
                        Http.doHttpGet(
                            updateChangeLogUrl!!, true
                        ), StandardCharsets.UTF_8
                    )
                    if (desc.length > 1000) {
                        desc = desc.substring(0, 1000)
                    }
                    updateChangeLog = desc
                } catch (ioe: IOException) {
                    updateChangeLog = ""
                }
                updateChecksum = jsonUpdate.optString("checksum")
                val updateZipUrlForReals = updateZipUrl
                if (updateZipUrlForReals!!.isEmpty()) throw FastException.INSTANCE
                updateVersion = PropUtils.shortenVersionName(
                    updateZipUrlForReals.trim { it <= ' ' }, updateVersionCode
                )
                if (updateChangeLog.length > 1000) updateChangeLog = updateChangeLog.substring(1000)
                updateChangeLog = urlLinkify(updateChangeLog)
            } catch (e: Exception) {
                updateVersion = null
                updateVersionCode = Long.MIN_VALUE
                updateZipUrl = null
                updateChangeLog = ""
                updateChecksum = null
                Timber.w(e, "Failed update checking for module: %s", id)
            }
        }
    }
}