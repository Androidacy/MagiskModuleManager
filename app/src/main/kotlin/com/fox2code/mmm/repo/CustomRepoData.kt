/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.repo

import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

class CustomRepoData internal constructor(url: String?, cacheRoot: File?) : RepoData(
    url!!, cacheRoot!!
) {
    var loadedExternal = false

    var override: String? = null
    override val isEnabledByDefault: Boolean
        get() = override != null || loadedExternal

    @Throws(IOException::class, JSONException::class)
    fun quickPrePopulate() {
        val jsonObject = JSONObject(
            String(
                doHttpGet(
                    url,
                    false
                ), StandardCharsets.UTF_8
            )
        )
        // make sure there's at least a name and a modules or data object
        require(!(!jsonObject.has("name") || !jsonObject.has("modules") && !jsonObject.has("data"))) { "Invalid repo: $url" }
        name = jsonObject.getString("name").trim { it <= ' ' }
        website = jsonObject.optString("website")
        support = jsonObject.optString("support")
        donate = jsonObject.optString("donate")
        submitModule = jsonObject.optString("submitModule")
    }

    fun toJSON(): Any? {
        return try {
            JSONObject()
                .put("id", preferenceId)
                .put("name", name)
                .put("website", website)
                .put("support", support)
                .put("donate", donate)
                .put("submitModule", submitModule)
        } catch (ignored: JSONException) {
            null
        }
    }
}