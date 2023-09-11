/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("ktConcatNullable")

package com.fox2code.mmm.utils.io.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.widget.Toast
import androidx.webkit.WebViewCompat
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyUtil
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.utils.io.Files.Companion.makeBuffer
import com.google.net.cronet.okhttptransport.CronetInterceptor
import io.sentry.android.okhttp.SentryOkHttpInterceptor
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder.*
import okhttp3.Request
import okhttp3.Request.*
import okhttp3.Request.Builder.*
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.Response.*
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.dnsoverhttps.DnsOverHttps.Builder.*
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.*
import okio.BufferedSink
import org.chromium.net.CronetEngine
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Objects
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

enum class Http {;

    interface ProgressListener {
        fun onUpdate(downloaded: Int, total: Int, done: Boolean)
    }

    /**
     * FallBackDNS store successful DNS request to return them later
     * can help make the app to work later when the current DNS system
     * isn't functional or available.
     *
     * Note: DNS Cache is stored in user data.
     */
    private class FallBackDNS(context: Context, parent: Dns, vararg fallbacks: String?) : Dns {
        private val parent: Dns
        private val sharedPreferences: SharedPreferences
        private val fallbacks: HashSet<String>
        private val fallbackCache: HashMap<String, List<InetAddress>>

        init {
            sharedPreferences = context.getSharedPreferences("mmm_dns", Context.MODE_PRIVATE)
            this.parent = parent
            this.fallbacks =
                HashSet(listOf(*fallbacks)).toString().replaceAfter("]", "").replace("[", "")
                    .split(",").toHashSet()
            fallbackCache = HashMap()
        }

        @Suppress("SENSELESS_COMPARISON")
        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> {
            return if (fallbacks.contains(hostname)) {
                var addresses: List<InetAddress>
                synchronized(fallbackCache) {
                    addresses = fallbackCache[hostname]!!
                    if (addresses != null) return addresses
                    try {
                        addresses = parent.lookup(hostname)
                        if (addresses.isEmpty() || addresses[0].isLoopbackAddress) throw UnknownHostException(
                            hostname
                        )
                        fallbackCache[hostname] = addresses
                        sharedPreferences.edit()
                            .putString(hostname.replace('.', '_'), toString(addresses)).apply()
                    } catch (e: UnknownHostException) {
                        val key = sharedPreferences.getString(hostname.replace('.', '_'), "")
                        if (key!!.isEmpty()) throw e
                        try {
                            addresses = fromString(key)
                            fallbackCache.put(hostname, addresses)
                        } catch (e2: UnknownHostException) {
                            sharedPreferences.edit().remove(hostname.replace('.', '_')).apply()
                            throw e
                        }
                    }
                }
                addresses
            } else {
                parent.lookup(hostname)
            }
        }

        fun cleanDnsCache() {
            synchronized(fallbackCache) { fallbackCache.clear() }
        }

        companion object {
            private fun toString(inetAddresses: List<InetAddress>): String {
                if (inetAddresses.isEmpty()) return ""
                val inetAddressIterator = inetAddresses.iterator()
                val stringBuilder = StringBuilder()
                while (true) {
                    stringBuilder.append(inetAddressIterator.next().hostAddress)
                    if (!inetAddressIterator.hasNext()) return stringBuilder.toString()
                    stringBuilder.append("|")
                }
            }

            @Throws(UnknownHostException::class)
            private fun fromString(string: String): List<InetAddress> {
                if (string.isEmpty()) return emptyList()
                val strings =
                    string.split("\\|".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                val inetAddresses = ArrayList<InetAddress>(strings.size)
                for (address in strings) {
                    inetAddresses.add(InetAddress.getByName(address))
                }
                return inetAddresses
            }
        }
    }

    private class JsonRequestBody private constructor(val data: ByteArray) : RequestBody() {
        override fun contentType(): MediaType {
            return JSON_MEDIA_TYPE
        }

        override fun contentLength(): Long {
            return data.size.toLong()
        }

        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
            sink.write(data)
        }

        companion object {
            private val JSON_MEDIA_TYPE: MediaType =
                "application/json; charset=utf-8".toMediaTypeOrNull()!!
            private val EMPTY = JsonRequestBody(ByteArray(0))
            fun from(data: String?): JsonRequestBody {
                return if (data.isNullOrEmpty()) {
                    EMPTY
                } else JsonRequestBody(data.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    companion object {
        private var connectivityListener: ConnectivityManager.NetworkCallback? = null
        private var lastConnectivityResult: Boolean = false
        private var lastConnectivityCheck: Long = 0
        private var limitedRetries: Int = 0
        private var httpClient: OkHttpClient? = null
        private var httpClientDoH: OkHttpClient? = null
        private var httpClientWithCache: OkHttpClient? = null
        private var httpClientWithCacheDoH: OkHttpClient? = null
        private var fallbackDNS: FallBackDNS? = null

        var androidacyUA: String? = null
        private var hasWebView = false
        private var needCaptchaAndroidacyHost: String? = null
        private var doh = false

        init {
            val mainApplication = MainApplication.INSTANCE
            if (mainApplication == null) {
                val error = Error("Initialized Http too soon!")
                error.fillInStackTrace()
                Timber.e(error, "Initialized Http too soon!")
                System.out.flush()
                System.err.flush()
                try {
                    Os.kill(Os.getpid(), 9)
                } catch (e: ErrnoException) {
                    exitProcess(9)
                }
                throw error
            }
            var cookieManager: CookieManager? = null
            try {
                cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.flush() // Make sure the instance work
            } catch (t: Exception) {
                Timber.e(t, "No WebView support!")
                // show a toast
                val context: Context = mainApplication.applicationContext
                MainActivity.getAppCompatActivity(context).runOnUiThread {
                    Toast.makeText(
                        mainApplication, R.string.error_creating_cookie_database, Toast.LENGTH_LONG
                    ).show()
                }
            }
            // get webview version
            var webviewVersion = "0.0.0"
            val pi = WebViewCompat.getCurrentWebViewPackage(mainApplication)
            if (pi != null) {
                webviewVersion = pi.versionName
            }
            // webviewVersionMajor is the everything before the first dot
            val webviewVersionCode: Int
            // parse webview version
            // get the first dot
            val dot: Int = webviewVersion.indexOf('.')
            webviewVersionCode = if (dot == -1) {
                // no dot, use the whole string
                webviewVersion.toInt()
            } else {
                // use the first dot
                webviewVersion.substring(
                    0, dot
                ).toInt()
            }
            if (BuildConfig.DEBUG) Timber.d(
                "Webview version: %s (%d)", webviewVersion, webviewVersionCode
            )
            hasWebView =
                cookieManager != null && webviewVersionCode >= 83 // 83 is the first version Androidacy supports due to errors in 82
            val httpclientBuilder = OkHttpClient.Builder()
            // Default is 10, extend it a bit for slow mobile connections.
            httpclientBuilder.connectTimeout(5, TimeUnit.SECONDS)
            httpclientBuilder.writeTimeout(10, TimeUnit.SECONDS)
            httpclientBuilder.readTimeout(15, TimeUnit.SECONDS)
            httpclientBuilder.proxy(Proxy.NO_PROXY) // Do not use system proxy
            var dns = Dns.SYSTEM
            try {
                val cloudflareBootstrap = arrayOf(
                    InetAddress.getByName("162.159.36.1"),
                    InetAddress.getByName("162.159.46.1"),
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("162.159.132.53"),
                    InetAddress.getByName("2606:4700:4700::1111"),
                    InetAddress.getByName("2606:4700:4700::1001"),
                    InetAddress.getByName("2606:4700:4700::0064"),
                    InetAddress.getByName("2606:4700:4700::6400")
                )
                dns = Dns { s: String? ->
                    if ("cloudflare-dns.com" == s) {
                        return@Dns listOf<InetAddress>(*cloudflareBootstrap)
                    }
                    Dns.SYSTEM.lookup(s!!)
                }
                httpclientBuilder.dns(dns)
                val cookieJar = WebkitCookieManagerProxy()
                httpclientBuilder.cookieJar(cookieJar)
                dns = DnsOverHttps.Builder().client(httpclientBuilder.build())
                    .url("https://cloudflare-dns.com/dns-query".toHttpUrl()).bootstrapDnsHosts(
                        *cloudflareBootstrap
                    ).build()
            } catch (e: UnknownHostException) {
                Timber.e(e, "Failed to init DoH")
            } catch (e: RuntimeException) {
                Timber.e(e, "Failed to init DoH")
            }
            // User-Agent format was agreed on telegram
            androidacyUA = if (hasWebView) {
                WebSettings.getDefaultUserAgent(mainApplication)
                    .replace("wv", "") + " FoxMMM/" + BuildConfig.VERSION_CODE
            } else {
                "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.DEVICE + ")" + " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.131 Mobile Safari/537.36" + " FoxMmm/" + BuildConfig.VERSION_CODE
            }
            httpclientBuilder.addInterceptor(Interceptor { chain: Interceptor.Chain? ->
                val request: Request.Builder = chain!!.request().newBuilder()
                request.header("Upgrade-Insecure-Requests", "1")
                val host: String = chain.request().url.host
                if (host.endsWith(".androidacy.com")) {
                    request.header("User-Agent", androidacyUA!!)
                } else if (!(host == "github.com" || host.endsWith(
                        ".github.com"
                    ) || host.endsWith(".jsdelivr.net") || host.endsWith(
                        ".githubusercontent.com"
                    ))
                ) {
                    if (peekMagiskPath() != null) {
                        request.header(
                            "User-Agent",  // Declare Magisk version to the server
                            "Magisk/" + peekMagiskVersion()
                        )
                    }
                }
                if (chain.request().header("Accept-Language") == null) {
                    request.header(
                        "Accept-Language",  // Send system language to the server
                        mainApplication.resources.configuration.locales.get(0).toLanguageTag()
                    )
                }
                // add client hints
                request.header("Sec-CH-UA", androidacyUA!!)
                request.header("Sec-CH-UA-Mobile", "?1")
                request.header("Sec-CH-UA-Platform", "Android")
                request.header(
                    "Sec-CH-UA-Platform-Version", Build.VERSION.RELEASE
                )
                request.header(
                    "Sec-CH-UA-Arch", Build.SUPPORTED_ABIS[0]
                )
                request.header(
                    "Sec-CH-UA-Full-Version", BuildConfig.VERSION_NAME
                )
                request.header("Sec-CH-UA-Model", Build.DEVICE)
                request.header(
                    "Sec-CH-UA-Bitness",
                    if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64" else "32"
                )
                chain.proceed(request.build())
            })

            // for debug builds, add a logging interceptor
            // this spams the logcat, so it's disabled by default and hidden behind a build config flag
            if (BuildConfig.DEBUG && BuildConfig.DEBUG_HTTP) {
                Timber.w("HTTP logging is enabled. Performance may be impacted.")
                val loggingInterceptor = HttpLoggingInterceptor()
                loggingInterceptor.setLevel(Level.BODY)
                httpclientBuilder.addInterceptor(loggingInterceptor)
            }

            // add sentry interceptor
            httpclientBuilder.addInterceptor(SentryOkHttpInterceptor())

            // Add cronet interceptor
            // init cronet
            try {
                // Load the cronet library
                val builder: CronetEngine.Builder =
                    CronetEngine.Builder(mainApplication.applicationContext)
                builder.enableBrotli(true)
                builder.enableHttp2(true)
                builder.enableQuic(true)
                // Cache size is 10MB
                // Make the directory if it does not exist
                val cacheDir = File(mainApplication.cacheDir, "cronet")
                if (!cacheDir.exists()) {
                    if (!cacheDir.mkdirs()) {
                        throw IOException("Failed to create cronet cache directory")
                    }
                }
                builder.setStoragePath(
                    mainApplication.cacheDir.absolutePath + "/cronet"
                )
                builder.enableHttpCache(
                    CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, (10 * 1024 * 1024).toLong()
                )
                // Add quic hint
                builder.addQuicHint("github.com", 443, 443)
                builder.addQuicHint("githubusercontent.com", 443, 443)
                builder.addQuicHint("jsdelivr.net", 443, 443)
                builder.addQuicHint("androidacy.com", 443, 443)
                builder.addQuicHint("sentry.io", 443, 443)
                val engine: CronetEngine = builder.build()
                httpclientBuilder.addInterceptor(
                    CronetInterceptor.newBuilder(
                        engine
                    ).build()
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to init cronet")
                // Gracefully fallback to okhttp
            }
            // Fallback DNS cache responses in case request fail but already succeeded once in the past
            fallbackDNS = FallBackDNS(
                mainApplication,
                dns,
                "github.com",
                "api.github.com",
                "raw.githubusercontent.com",
                "camo.githubusercontent.com",
                "user-images.githubusercontent.com",
                "cdn.jsdelivr.net",
                "img.shields.io",
                "magisk-modules-repo.github.io",
                "www.androidacy.com",
                "api.androidacy.com",
                "production-api.androidacy.com"
            )
            httpclientBuilder.dns(Dns.SYSTEM)
            httpClient = followRedirects(httpclientBuilder, true).build()
            followRedirects(httpclientBuilder, false).build()
            httpclientBuilder.dns(fallbackDNS!!)
            httpClientDoH = followRedirects(httpclientBuilder, true).build()
            followRedirects(httpclientBuilder, false).build()
            httpclientBuilder.cache(
                Cache(
                    File(
                        mainApplication.cacheDir, "http_cache"
                    ), 16L * 1024L * 1024L
                )
            ) // 16Mib of cache
            httpclientBuilder.dns(Dns.SYSTEM)
            httpClientWithCache = followRedirects(httpclientBuilder, true).build()
            httpclientBuilder.dns(fallbackDNS!!)
            httpClientWithCacheDoH = followRedirects(httpclientBuilder, true).build()
            Timber.i("Initialized Http successfully!")
            doh = MainApplication.isDohEnabled
        }

        private fun followRedirects(
            builder: OkHttpClient.Builder, followRedirects: Boolean
        ): OkHttpClient.Builder {
            return builder.followRedirects(followRedirects).followSslRedirects(followRedirects)
        }

        private fun getHttpClient(): OkHttpClient? {
            return if (doh) httpClientDoH else httpClient
        }

        fun getHttpClientWithCache(): OkHttpClient? {
            return if (doh) httpClientWithCacheDoH else httpClientWithCache
        }

        private fun checkNeedCaptchaAndroidacy(url: String, errorCode: Int) {
            if (errorCode == 403 && AndroidacyUtil.isAndroidacyLink(url)) {
                needCaptchaAndroidacyHost = Uri.parse(url).host
            }
        }

        fun needCaptchaAndroidacy(): Boolean {
            return needCaptchaAndroidacyHost != null
        }

        fun needCaptchaAndroidacyHost(): String? {
            return needCaptchaAndroidacyHost
        }

        fun markCaptchaAndroidacySolved() {
            needCaptchaAndroidacyHost = null
        }

        @SuppressLint("RestrictedApi")
        @Throws(IOException::class)
        fun doHttpGet(url: String, allowCache: Boolean): ByteArray {
            if (url.isEmpty()) {
                throw IOException("Empty URL")
            }
            var response: Response?
            response = try {
                (if (allowCache) getHttpClientWithCache() else getHttpClient())!!.newCall(
                    Request.Builder().url(
                        url
                    ).get().build()
                ).execute()
            } catch (e: IOException) {
                Timber.e(e, "Failed to post %s", url)
                // detect ssl errors, i.e., cert authority invalid by looking at the message
                if (e.message != null && e.message!!.contains("_CERT_")) {
                    MainApplication.INSTANCE!!.lastActivity!!.runOnUiThread {
                        // show toast
                        Toast.makeText(
                            MainApplication.INSTANCE, R.string.ssl_error, Toast.LENGTH_LONG
                        ).show()
                    }
                }
                throw HttpException(e.message, 0)
            }
            if (BuildConfig.DEBUG_HTTP) {
                if (BuildConfig.DEBUG) Timber.d("doHttpGet: request executed")
            }
            // 200/204 == success, 304 == cache valid
            if (response != null) {
                if (response.code != 200 && response.code != 204 && (response.code != 304 || !allowCache)) {
                    Timber.e(
                        "Failed to fetch " + url.replace(
                            "=[^&]*".toRegex(), "=****"
                        ) + " with code " + response.code
                    )
                    checkNeedCaptchaAndroidacy(url, response.code)
                    // If it's a 401, and an androidacy link, it's probably an invalid token
                    if (response.code == 401 && AndroidacyUtil.isAndroidacyLink(url)) {
                        // Regenerate the token
                        throw HttpException("Androidacy token is invalid", 401)
                    }
                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")
                        if (retryAfter != null) {
                            try {
                                val seconds = Integer.parseInt(retryAfter)
                                if (BuildConfig.DEBUG) Timber.d("Sleeping for $seconds seconds")
                                Thread.sleep(seconds * 1000L)
                            } catch (e: NumberFormatException) {
                                Timber.e(e, "Failed to parse Retry-After header")
                            } catch (e: InterruptedException) {
                                Timber.e(e, "Failed to sleep")
                            }
                        } else {// start with one second and try up to five times
                            if (limitedRetries < 5) {
                                limitedRetries++
                                if (BuildConfig.DEBUG) Timber.d("Sleeping for 1 second")
                                try {
                                    Thread.sleep(1000L * limitedRetries)
                                } catch (e: InterruptedException) {
                                    Timber.e(e, "Failed to sleep")
                                }
                                return doHttpGet(url, allowCache)
                            } else {
                                throw HttpException(response.code)
                            }
                        }
                    }
                    throw HttpException(response.code)
                }
            }
            if (BuildConfig.DEBUG_HTTP) {
                if (BuildConfig.DEBUG) Timber.d("doHttpGet: " + url.replace("=[^&]*".toRegex(), "=****") + " succeeded")
            }
            var responseBody = response?.body
            // Use cache api if used cached response
            if (response != null) {
                if (response.code == 304) {
                    response = response.cacheResponse
                    if (response != null) responseBody = response.body
                }
            }
            if (BuildConfig.DEBUG_HTTP) {
                if (responseBody != null) {
                    if (BuildConfig.DEBUG) Timber.d("doHttpGet: returning " + responseBody.contentLength() + " bytes")
                }
            }
            return responseBody?.bytes() ?: ByteArray(0)
        }

        @Suppress("unused")
        @Throws(IOException::class)
        fun doHttpPost(url: String, data: String, allowCache: Boolean): ByteArray {
            return doHttpPostRaw(url, data, allowCache) as ByteArray
        }

        @Throws(IOException::class)
        private fun doHttpPostRaw(url: String, data: String, allowCache: Boolean): Any {
            if (BuildConfig.DEBUG) Timber.d("POST %s", url)
            var response: Response?
            try {
                response =
                    (if (allowCache) getHttpClientWithCache() else getHttpClient())!!.newCall(
                        Request.Builder().url(url).post(
                            JsonRequestBody.from(data)
                        ).header("Content-Type", "application/json").build()
                    ).execute()
            } catch (e: IOException) {
                Timber.e(e, "Failed to post %s", url)
                // detect ssl errors, i.e., cert authority invalid by looking at the message
                if (e.message != null && e.message!!.contains("_CERT_")) {
                    MainApplication.INSTANCE!!.lastActivity!!.runOnUiThread {
                        // show toast
                        Toast.makeText(
                            MainApplication.INSTANCE, R.string.ssl_error, Toast.LENGTH_LONG
                        ).show()
                    }
                }
                throw HttpException(e.message, 0)
            }
            if (response.isRedirect) {
                // follow redirect with same method
                if (BuildConfig.DEBUG) Timber.d("doHttpPostRaw: following redirect: %s", response.header("Location"))
                response =
                    (if (allowCache) getHttpClientWithCache() else getHttpClient())!!.newCall(
                        Request.Builder().url(
                            Objects.requireNonNull<String?>(response.header("Location"))
                        ).post(
                            JsonRequestBody.from(data)
                        ).header("Content-Type", "application/json").build()
                    ).execute()
            }
            // 200/204 == success, 304 == cache valid
            if (response.code != 200 && response.code != 204 && (response.code != 304 || !allowCache)) {
                if (BuildConfig.DEBUG_HTTP) Timber.e("Failed to fetch " + url + ", code: " + response.code + ", body: " + response.body.string())
                checkNeedCaptchaAndroidacy(url, response.code)
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")
                    if (retryAfter != null) {
                        try {
                            val seconds = Integer.parseInt(retryAfter)
                            if (BuildConfig.DEBUG) Timber.d("Sleeping for $seconds seconds")
                            Thread.sleep(seconds * 1000L)
                        } catch (e: NumberFormatException) {
                            Timber.e(e, "Failed to parse Retry-After header")
                        } catch (e: InterruptedException) {
                            Timber.e(e, "Failed to sleep")
                        }
                    } else {// start with one second and try up to five times
                        if (limitedRetries < 5) {
                            limitedRetries++
                            if (BuildConfig.DEBUG) Timber.d("Sleeping for 1 second")
                            try {
                                Thread.sleep(1000L * limitedRetries)
                            } catch (e: InterruptedException) {
                                Timber.e(e, "Failed to sleep")
                            }
                            return doHttpPostRaw(url, data, allowCache)
                        } else {
                            Timber.e("Failed to fetch " + url + ", code: " + response.code)
                            throw HttpException(response.code)
                        }
                    }
                }
                throw HttpException(response.code)
            }
            var responseBody = response.body
            // Use cache api if used cached response
            if (response.code == 304) {
                response = response.cacheResponse
                if (response != null) responseBody = response.body
            }
            return responseBody.bytes()
        }

        @Throws(IOException::class)
        fun doHttpGet(url: String, progressListener: ProgressListener): ByteArray {
            val response: Response
            try {
                response =
                    getHttpClient()!!.newCall(Request.Builder().url(url).get().build()).execute()
            } catch (e: IOException) {
                Timber.e(e, "Failed to post %s", url)
                // detect ssl errors, i.e., cert authority invalid by looking at the message
                if (e.message != null && e.message!!.contains("_CERT_")) {
                    MainApplication.INSTANCE!!.lastActivity!!.runOnUiThread {
                        // show toast
                        Toast.makeText(
                            MainApplication.INSTANCE, R.string.ssl_error, Toast.LENGTH_LONG
                        ).show()
                    }
                }
                throw HttpException(e.message, 0)
            }
            if (response.code != 200 && response.code != 204) {
                Timber.e("Failed to fetch " + url + ", code: " + response.code)
                checkNeedCaptchaAndroidacy(url, response.code)
                // if error is 429, exponential backoff
                if (response.code == 429) {
                    val retryAfter = response.header("Retry-After")
                    if (retryAfter != null) {
                        try {
                            val seconds = Integer.parseInt(retryAfter)
                            if (BuildConfig.DEBUG) Timber.d("Sleeping for $seconds seconds")
                            Thread.sleep(seconds * 1000L)
                        } catch (e: NumberFormatException) {
                            Timber.e(e, "Failed to parse Retry-After header")
                        } catch (e: InterruptedException) {
                            Timber.e(e, "Failed to sleep")
                        }
                    } else {// start with one second and try up to five times
                        if (limitedRetries < 5) {
                            limitedRetries++
                            if (BuildConfig.DEBUG) Timber.d("Sleeping for 1 second")
                            try {
                                Thread.sleep(1000L * limitedRetries)
                            } catch (e: InterruptedException) {
                                Timber.e(e, "Failed to sleep")
                            }
                            return doHttpGet(url, progressListener)
                        } else {
                            Timber.e("Failed to fetch " + url + ", code: " + response.code)
                            throw HttpException(response.code)
                        }
                    }
                }
                throw HttpException(response.code)
            }
            val responseBody = Objects.requireNonNull(response.body)
            val inputStream = responseBody.byteStream()
            val buff = ByteArray(1024 * 4)
            var downloaded: Long = 0
            val target = responseBody.contentLength()
            val byteArrayOutputStream = makeBuffer(target)
            var divider = 1 // Make everything go in an int
            while (target / divider > Int.MAX_VALUE / 2) {
                divider *= 2
            }
            val updateInterval: Long = 100
            var nextUpdate = System.currentTimeMillis() + updateInterval
            var currentUpdate: Long
            Timber.i("Target: $target Divider: $divider")
            progressListener.onUpdate(0, (target / divider).toInt(), false)
            while (true) {
                val read = inputStream.read(buff)
                if (read == -1) break
                byteArrayOutputStream.write(buff, 0, read)
                downloaded += read.toLong()
                currentUpdate = System.currentTimeMillis()
                if (nextUpdate < currentUpdate) {
                    nextUpdate = currentUpdate + updateInterval
                    progressListener.onUpdate(
                        (downloaded / divider).toInt(), (target / divider).toInt(), false
                    )
                }
            }
            inputStream.close()
            progressListener.onUpdate(
                (downloaded / divider).toInt(), (target / divider).toInt(), true
            )
            return byteArrayOutputStream.toByteArray()
        }

        // dohttpget with progress listener but as lambda
        @Throws(IOException::class)
        fun doHttpGet(url: String, progressListener: (Int, Int, Boolean) -> Unit): ByteArray {
            return doHttpGet(url, object : ProgressListener {
                override fun onUpdate(downloaded: Int, total: Int, done: Boolean) {
                    progressListener(downloaded, total, done)
                }
            })
        }

        fun cleanDnsCache() {
            fallbackDNS?.cleanDnsCache()
        }

        fun setDoh(doh: Boolean) {
            Timber.i("DoH: " + Companion.doh + " -> " + doh)
            Companion.doh = doh
        }

        fun hasWebView(): Boolean {
            return hasWebView
        }

        fun hasConnectivity(context: Context): Boolean {
            // cache result for 10 seconds so we don't spam the system
            if (System.currentTimeMillis() - lastConnectivityCheck < 10000) {
                return lastConnectivityResult
            }
            // Check if we have internet connection using connectivity manager
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // are we connected to a network with internet capabilities?
            val networkCapabilities =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            val systemSaysYes = networkCapabilities != null && networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
            if (BuildConfig.DEBUG) Timber.d("System says we have internet: $systemSaysYes")
            // if we don't already have a listener, add one, so we can invalidate the cache when the network changes
            if (connectivityListener == null) {
                connectivityListener = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        if (BuildConfig.DEBUG) Timber.d("Network became available")
                        lastConnectivityCheck = 0
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        if (BuildConfig.DEBUG) Timber.d("Network became unavailable")
                        lastConnectivityCheck = 0
                    }
                }
                connectivityManager.registerDefaultNetworkCallback(connectivityListener!!)
            }
            if (!systemSaysYes) return false
            // check ourselves
            val hasInternet = try {
                val resp = doHttpGet("https://production-api.androidacy.com/cdn-cgi/trace", false)
                val respString = String(resp)
                // resp should include that scheme is https and h is production-api.androidacy.com
                respString.contains("scheme=https") && respString.contains("h=production-api.androidacy.com")
            } catch (e: HttpException) {
                Timber.e(e, "Failed to check internet connection")
                false
            }
            if (BuildConfig.DEBUG) Timber.d("We say we have internet: $hasInternet")
            lastConnectivityCheck = System.currentTimeMillis()
            @Suppress("KotlinConstantConditions")
            lastConnectivityResult =
                systemSaysYes && hasInternet
            return lastConnectivityResult
        }
    }
}