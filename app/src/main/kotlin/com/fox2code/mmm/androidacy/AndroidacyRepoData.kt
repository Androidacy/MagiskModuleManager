/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.androidacy

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.fingerprintjs.android.fingerprint.Fingerprinter
import com.fingerprintjs.android.fingerprint.FingerprinterFactory.create
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.getSharedPreferences
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.hideToken
import com.fox2code.mmm.androidacy.AndroidacyUtil.Companion.isAndroidacyLink
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.repo.RepoData
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.utils.io.PropUtils.Companion.applyFallbacks
import com.fox2code.mmm.utils.io.PropUtils.Companion.isInvalidURL
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.fox2code.mmm.utils.io.net.Http.Companion.needCaptchaAndroidacy
import com.fox2code.mmm.utils.io.net.HttpException
import com.fox2code.mmm.utils.io.net.HttpException.Companion.shouldTimeout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.HttpUrl.Builder
import okhttp3.HttpUrl.Builder.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.max

class AndroidacyRepoData(cacheRoot: File?, testMode: Boolean) : RepoData(
    if (testMode) RepoManager.ANDROIDACY_TEST_MAGISK_REPO_ENDPOINT else RepoManager.ANDROIDACY_MAGISK_REPO_ENDPOINT,
    cacheRoot!!
) {
    private val clientID = BuildConfig.ANDROIDACY_CLIENT_ID
    private val testMode: Boolean
    private val host: String

    @JvmField
    var userInfo = arrayOf(arrayOf("role", null), arrayOf("permissions", null))

    @JvmField
    var memberLevel: String? = null

    // Avoid spamming requests to Androidacy
    private var androidacyBlockade: Long = 0

    init {
        defaultName = "Androidacy Modules Repo"
        defaultWebsite = RepoManager.ANDROIDACY_MAGISK_REPO_HOMEPAGE
        defaultSupport = "https://t.me/androidacy_discussions"
        defaultDonate =
            "https://www.androidacy.com/membership-account/membership-checkout/?level=2&discount_code=FOX2CODE&utm_souce=foxmmm&utm_medium=android-app&utm_campaign=fox-upgrade-promo"
        defaultSubmitModule = "https://www.androidacy.com/module-repository-applications/"
        host = if (testMode) "staging-api.androidacy.com" else "production-api.androidacy.com"
        this.testMode = testMode
    }

    @Throws(IOException::class)
    fun isValidToken(token: String?): Boolean {
        val deviceId = generateDeviceId()
        return try {
            val resp = doHttpGet(
                "https://$host/auth/me?token=$token&device_id=$deviceId&client_id=$clientID",
                false
            )
            // response is JSON
            val jsonObject = JSONObject(String(resp))
            memberLevel = jsonObject.getString("role")
            Timber.d("Member level: %s", memberLevel)
            val memberPermissions = jsonObject.getJSONArray("permissions")
            // set role and permissions on userInfo property
            userInfo = arrayOf(
                arrayOf("role", memberLevel),
                arrayOf("permissions", memberPermissions.toString())
            )
            true
        } catch (e: HttpException) {
            if (e.errorCode == 401) {
                Timber.w("Invalid token, resetting...")
                // Remove saved preference
                val editor = getSharedPreferences("androidacy")!!.edit()
                editor.remove("pref_androidacy_api_token")
                editor.apply()
                return false
            } else {
                val handler = Handler(Looper.getMainLooper())
                handler.post {
                    Toast.makeText(
                        INSTANCE,
                        INSTANCE!!.getString(R.string.androidacy_api_error, e.errorCode),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Timber.w(e)
            false
        } catch (e: JSONException) {
            // response is not JSON
            Timber.w("Invalid token, resetting...")
            Timber.w(e)
            // Remove saved preference
            val editor = getSharedPreferences("androidacy")!!.edit()
            editor.remove("pref_androidacy_api_token")
            editor.apply()
            false
        }
    }

    /**
     * Request a new token from the server and save it to the shared preferences
     * @return String token
     */
    @Throws(IOException::class, JSONException::class)
    fun requestNewToken(): String {
        val deviceId = generateDeviceId()
        val resp = doHttpGet(
            "https://" + host + "/auth/register?device_id=" + deviceId + "&client_id=" + BuildConfig.ANDROIDACY_CLIENT_ID,
            false
        )
        // response is JSON
        val jsonObject = JSONObject(String(resp))
        val token = jsonObject.getString("token")
        // Save the token to the shared preferences
        val editor = getSharedPreferences("androidacy")!!.edit()
        editor.putString("pref_androidacy_api_token", token)
        editor.apply()
        return token
    }

    @SuppressLint("RestrictedApi", "BinaryOperationInTimber")
    override fun prepare(): Boolean {
        // If ANDROIDACY_CLIENT_ID is not set or is empty, disable this repo and return
        @Suppress("KotlinConstantConditions")
        if (BuildConfig.ANDROIDACY_CLIENT_ID == "") {
            val editor = getSharedPreferences("mmm")!!.edit()
            editor.putBoolean("pref_androidacy_repo_enabled", false)
            editor.apply()
            Timber.w("ANDROIDACY_CLIENT_ID is empty, disabling AndroidacyRepoData 2")
            return false
        }
        if (needCaptchaAndroidacy()) return false
        // Implementation details discussed on telegram
        // First, ping the server to check if it's alive
        try {
            val connection = URL("https://$host/ping").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.readTimeout = 5000
            connection.connect()
            if (connection.responseCode != 200 && connection.responseCode != 204) {
                // If it's a 400, the app is probably outdated. Show a snackbar suggesting user update app and webview
                if (connection.responseCode == 400) {
                    // Show a dialog using androidacy_update_needed string
                    INSTANCE?.let { MaterialAlertDialogBuilder(it) }!!
                        .setTitle(R.string.androidacy_update_needed)
                        .setMessage(
                            R.string.androidacy_update_needed_message
                        )
                        .setPositiveButton(R.string.update) { _: DialogInterface?, _: Int ->
                            // Open the app's page on the Play Store
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.data =
                                Uri.parse("https://www.androidacy.com/downloads/?view=FoxMMM&utm_source=foxmnm&utm_medium=app&utm_campaign=android-app")
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            INSTANCE!!.startActivity(intent)
                        }.setNegativeButton(R.string.cancel, null).show()
                }
                return false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to ping server")
            return false
        }
        val time = System.currentTimeMillis()
        if (androidacyBlockade > time) return true // fake it till you make it. Basically,
        // don't fail just because we're rate limited. API and web rate limits are different.
        androidacyBlockade = time + 30000L
        try {
            if (token == null) {
                token =
                    getSharedPreferences("androidacy")?.getString("pref_androidacy_api_token", null)
                if (token != null && !isValidToken(token)) {
                    Timber.i("Token expired or invalid, requesting new one...")
                    token = null
                } else {
                    Timber.i("Using cached token")
                }
            } else if (!isValidToken(token)) {
                Timber.i("Token expired, requesting new one...")
                token = null
            } else {
                Timber.i("Using validated cached token")
            }
        } catch (e: IOException) {
            if (shouldTimeout(e)) {
                Timber.e(e, "We are being rate limited!")
                androidacyBlockade = time + 3600000L
            }
            return false
        }
        if (token == null) {
            Timber.i("Token is null, requesting new one...")
            try {
                Timber.i("Requesting new token...")
                // POST json request to https://production-api.androidacy.com/auth/register
                token = requestNewToken()
                // Parse token
                try {
                    val jsonObject = JSONObject(token!!)
                    // log last four of token, replacing the rest with asterisks
                    token = jsonObject.getString("token")
                    val tempToken = token!!
                    Timber.d(
                        "Token: %s",
                        tempToken.substring(0, tempToken.length - 4)
                            .replace(".".toRegex(), "*") + tempToken.substring(
                            tempToken.length - 4
                        )
                    )
                    memberLevel = jsonObject.getString("role")
                    Timber.d("Member level: %s", memberLevel)
                } catch (e: JSONException) {
                    Timber.e(e, "Failed to parse token: %s", token)
                    // Show a toast
                    val mainLooper = Looper.getMainLooper()
                    val handler = Handler(mainLooper)
                    handler.post {
                        Toast.makeText(
                            INSTANCE,
                            R.string.androidacy_failed_to_parse_token,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return false
                }
                // Ensure token is valid
                if (!isValidToken(token)) {
                    Timber.e("Failed to validate token")
                    // Show a toast
                    val mainLooper = Looper.getMainLooper()
                    val handler = Handler(mainLooper)
                    handler.post {
                        Toast.makeText(
                            INSTANCE,
                            R.string.androidacy_failed_to_validate_token,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return false
                } else {
                    // Save token to shared preference
                    val editor = getSharedPreferences("androidacy")!!.edit()
                    editor.putString("pref_androidacy_api_token", token)
                    editor.apply()
                    Timber.i("Token saved to shared preference")
                }
            } catch (e: Exception) {
                if (shouldTimeout(e)) {
                    Timber.e(e, "We are being rate limited!")
                    androidacyBlockade = time + 3600000L
                }
                Timber.e(e, "Failed to get a new token")
                return false
            }
        }
        return true
    }

    @Suppress("NAME_SHADOWING")
    @Throws(JSONException::class)
    override fun populate(jsonObject: JSONObject): List<RepoModule>? {
        var jsonObject = jsonObject
        Timber.d("AndroidacyRepoData populate start")
        val name = jsonObject.optString("name", "Androidacy Modules Repo")
        val nameForModules =
            if (name.endsWith(" (Official)")) name.substring(0, name.length - 11) else name
        val jsonArray: JSONArray = try {
            jsonObject.getJSONArray("data")
        } catch (e: JSONException) {
            // probably using modules key since it's cached
            try {
                jsonObject.getJSONArray("modules")
            } catch (e2: JSONException) {
                // we should never get here, bail out
                Timber.e(e2, "Failed to parse modules")
                return null
            }
        }
        for (repoModule in moduleHashMap.values) {
            repoModule.processed = false
        }
        val newModules = ArrayList<RepoModule>()
        val len = jsonArray.length()
        var lastLastUpdate: Long = 0
        for (i in 0 until len) {
            jsonObject = jsonArray.getJSONObject(i)
            val moduleId: String = try {
                jsonObject.getString("codename")
            } catch (e: JSONException) {
                Timber.e(
                    "Module %s has no codename or json %s is invalid",
                    jsonObject.optString("codename", "Unknown"),
                    jsonObject.toString()
                )
                continue
            }
            // Normally, we'd validate the module id here, but we don't need to because the server does it for us
            val lastUpdate: Long = try {
                jsonObject.getLong("updated_at") * 1000
            } catch (e: JSONException) {
                jsonObject.getLong("lastUpdate") * 1000
            }
            lastLastUpdate = max(lastLastUpdate, lastUpdate)
            var repoModule = moduleHashMap[moduleId]
            if (repoModule == null) {
                repoModule = RepoModule(this, moduleId)
                repoModule.moduleInfo.flags = 0
                moduleHashMap[moduleId] = repoModule
                newModules.add(repoModule)
            } else {
                if (repoModule.lastUpdated < lastUpdate) {
                    newModules.add(repoModule)
                }
            }
            repoModule.processed = true
            repoModule.lastUpdated = lastUpdate
            repoModule.repoName = nameForModules
            repoModule.zipUrl = filterURL(jsonObject.optString("zipUrl", ""))
            repoModule.notesUrl = filterURL(jsonObject.optString("notesUrl", ""))
            if (repoModule.zipUrl == null) {
                repoModule.zipUrl =  // Fallback url in case the API doesn't have zipUrl
                    "https://$host/magisk/info/$moduleId"
            }
            if (repoModule.notesUrl == null) {
                repoModule.notesUrl =  // Fallback url in case the API doesn't have notesUrl
                    "https://$host/magisk/readme/$moduleId"
            }
            repoModule.zipUrl = injectToken(repoModule.zipUrl)
            repoModule.notesUrl = injectToken(repoModule.notesUrl)
            repoModule.qualityText = R.string.module_downloads
            repoModule.qualityValue = jsonObject.optInt("downloads", 0)
            if (repoModule.qualityValue == 0) {
                repoModule.qualityValue = jsonObject.optInt("stats", 0)
            }
            val checksum = jsonObject.optString("checksum", "")
            repoModule.checksum = checksum.ifEmpty { null }
            val moduleInfo = repoModule.moduleInfo
            moduleInfo.name = jsonObject.getString("name")
            moduleInfo.versionCode = jsonObject.getLong("versionCode")
            moduleInfo.version = jsonObject.optString("version", "v" + moduleInfo.versionCode)
            moduleInfo.author = jsonObject.optString("author", "Unknown")
            moduleInfo.description = jsonObject.optString("description", "")
            moduleInfo.minApi = jsonObject.getInt("minApi")
            moduleInfo.maxApi = jsonObject.getInt("maxApi")
            val minMagisk = jsonObject.getString("minMagisk")
            try {
                val c = minMagisk.indexOf('.')
                if (c == -1) {
                    moduleInfo.minMagisk = minMagisk.toInt()
                } else {
                    moduleInfo.minMagisk =  // Allow 24.1 to mean 24100
                        minMagisk.substring(0, c).toInt() * 1000 + minMagisk.substring(c + 1)
                            .toInt() * 100
                }
            } catch (e: Exception) {
                moduleInfo.minMagisk = 0
            }
            moduleInfo.needRamdisk = jsonObject.optBoolean("needRamdisk", false)
            moduleInfo.changeBoot = jsonObject.optBoolean("changeBoot", false)
            moduleInfo.mmtReborn = jsonObject.optBoolean("mmtReborn", false)
            moduleInfo.support = filterURL(jsonObject.optString("support"))
            moduleInfo.donate = filterURL(jsonObject.optString("donate"))
            moduleInfo.safe = jsonObject.has("vt_status") && jsonObject.getString("vt_status")
                .equals("clean", ignoreCase = true) || jsonObject.optBoolean("safe", false)
            val config = jsonObject.optString("config", "")
            moduleInfo.config = config.ifEmpty { null }
            applyFallbacks(moduleInfo) // Apply fallbacks
        }
        val moduleInfoIterator = moduleHashMap.values.iterator()
        while (moduleInfoIterator.hasNext()) {
            val repoModule = moduleInfoIterator.next()
            if (!repoModule.processed) {
                moduleInfoIterator.remove()
            } else {
                repoModule.moduleInfo.verify()
            }
        }
        lastUpdate = lastLastUpdate
        this.name = name
        website = jsonObject.optString("website")
        support = jsonObject.optString("support")
        donate = jsonObject.optString("donate")
        submitModule = jsonObject.optString("submitModule")
        return newModules
    }

    override fun storeMetadata(repoModule: RepoModule, data: ByteArray?) {}
    override fun tryLoadMetadata(repoModule: RepoModule): Boolean {
        if (moduleHashMap.containsKey(repoModule.id)) {
            repoModule.moduleInfo.flags =
                repoModule.moduleInfo.flags and ModuleInfo.FLAG_METADATA_INVALID.inv()
            return true
        }
        repoModule.moduleInfo.flags =
            repoModule.moduleInfo.flags or ModuleInfo.FLAG_METADATA_INVALID
        return false
    }

    override fun getUrl(): String {
        return if (token == null) url else url + "?token=" + token + "&v=" + BuildConfig.VERSION_CODE + "&c=" + BuildConfig.VERSION_NAME + "&device_id=" + generateDeviceId() + "&client_id=" + BuildConfig.ANDROIDACY_CLIENT_ID
    }

    @Suppress("NAME_SHADOWING")
    private fun injectToken(url: String?): String? {
        // Do not inject token for non Androidacy urls
        var url = url
        if (!isAndroidacyLink(url)) return url
        if (testMode) {
            if (url!!.startsWith("https://production-api.androidacy.com/")) {
                Timber.e("Got non test mode url: %s", hideToken(url))
                url = "https://staging-api.androidacy.com/" + url.substring(38)
            }
        } else {
            if (url!!.startsWith("https://staging-api.androidacy.com/")) {
                Timber.e("Got test mode url: %s", hideToken(url))
                url = "https://production-api.androidacy.com/" + url.substring(35)
            }
        }
        val token = "token=$token"
        val deviceId = "device_id=" + generateDeviceId()
        if (!url.contains(token)) {
            return if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                "$url&$token"
            } else {
                "$url?$token"
            }
        }
        return if (!url.contains(deviceId)) {
            if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                "$url&$deviceId"
            } else {
                "$url?$deviceId"
            }
        } else url
    }

    override var name: String?
        get() = if (testMode) super.name + " (Test Mode)" else super.name!!
        set(name) {
            super.name = name
        }

    fun setToken(token: String?) {
        if (hasWebView()) {
            Companion.token = token
        }
    }

    companion object {
        private var ANDROIDACY_DEVICE_ID: String? = null
        var token =
            getSharedPreferences("androidacy")!!.getString("pref_androidacy_api_token", null)

        init {
            @Suppress("LocalVariableName") val OK_HTTP_URL_BUILDER: Builder =
                Builder().scheme("https")
            // Using HttpUrl.Builder.host(String) crash the app
            OK_HTTP_URL_BUILDER.host("production-api.androidacy.com")
            OK_HTTP_URL_BUILDER.build()
        }

        @JvmStatic
        val instance: AndroidacyRepoData
            get() = RepoManager.getINSTANCE()!!.androidacyRepoData!!

        private fun filterURL(url: String?): String? {
            return if (url.isNullOrEmpty() || isInvalidURL(url)) {
                null
            } else url
        }

        // Generates a unique device ID. This is used to identify the device in the API for rate
        // limiting and fraud detection.
        fun generateDeviceId(): String? {
            // first, check if ANDROIDACY_DEVICE_ID is already set
            if (ANDROIDACY_DEVICE_ID != null) {
                return ANDROIDACY_DEVICE_ID
            }
            // Try to get the device ID from the shared preferences
            val sharedPreferences = getSharedPreferences("androidacy")
            val deviceIdPref =
                sharedPreferences!!.getString("device_id_v2", null)
            return if (deviceIdPref != null) {
                ANDROIDACY_DEVICE_ID = deviceIdPref
                deviceIdPref
            } else {
                val fp = create(INSTANCE!!.applicationContext)
                fp.getFingerprint(Fingerprinter.Version.V_5) { fingerprint: String? ->
                    ANDROIDACY_DEVICE_ID = fingerprint
                    // use fingerprint
                    // Save the device ID to the shared preferences
                    val editor = sharedPreferences.edit()
                    editor.putString("device_id_v2", ANDROIDACY_DEVICE_ID)
                    editor.apply()
                }
                // wait for up to 5 seconds for the fingerprint to be generated (ANDROIDACY_DEVICE_ID to be set)
                val startTime = System.currentTimeMillis()
                while (ANDROIDACY_DEVICE_ID == null && System.currentTimeMillis() - startTime < 5000) {
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (ANDROIDACY_DEVICE_ID == null) {
                    // fingerprint generation failed, use a random UUID
                    ANDROIDACY_DEVICE_ID = UUID.randomUUID().toString()
                }
                ANDROIDACY_DEVICE_ID
            }
        }
    }
}