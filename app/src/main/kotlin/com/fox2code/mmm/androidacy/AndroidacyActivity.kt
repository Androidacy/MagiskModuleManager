/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("ktConcatNullable")

package com.fox2code.mmm.androidacy

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ConsoleMessage.MessageLevel
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks.Companion.checkConfigTargetExists
import com.fox2code.mmm.XHooks.Companion.onWebViewInitialize
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.io.net.Http
import com.fox2code.mmm.utils.io.net.Http.Companion.androidacyUA
import com.fox2code.mmm.utils.io.net.Http.Companion.doHttpGet
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.fox2code.mmm.utils.io.net.Http.Companion.markCaptchaAndroidacySolved
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Per Androidacy repo implementation agreement, no request of this WebView shall be modified.
 */
class AndroidacyActivity : FoxActivity() {
    private var moduleFile: File? = null

    @JvmField
    var webView: WebView? = null
    var webViewNote: TextView? = null
    private var androidacyWebAPI: AndroidacyWebAPI? = null
    var progressIndicator: LinearProgressIndicator? = null

    @JvmField
    var backOnResume = false
    var downloadMode = false

    @SuppressLint(
        "SetJavaScriptEnabled",
        "JavascriptInterface",
        "RestrictedApi",
        "ClickableViewAccessibility"
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        moduleFile = File(this.cacheDir, "module.zip")
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(MainApplication.INSTANCE!!.tracker)
        val intent = this.intent
        var uri: Uri? = intent.data
        @Suppress("KotlinConstantConditions")
        if (!MainApplication.checkSecret(intent) || intent.data.also { uri = it!! } == null) {
            Timber.w("Impersonation detected")
            forceBackPressed()
            return
        }
        var url = uri.toString()
        if (!AndroidacyUtil.isAndroidacyLink(url, uri!!)) {
            Timber.w("Calling non androidacy link in secure WebView: %s", url)
            forceBackPressed()
            return
        }
        if (!hasWebView()) {
            Timber.w("No WebView found to load url: %s", url)
            forceBackPressed()
            return
        }
        // if action bar is shown, hide it
        hideActionBar()
        markCaptchaAndroidacySolved()
        if (!url.contains(AndroidacyUtil.REFERRER)) {
            url = if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                url + '&' + AndroidacyUtil.REFERRER
            } else {
                url + '?' + AndroidacyUtil.REFERRER
            }
        }
        // Add token to url if not present
        val token = uri!!.getQueryParameter("token")
        if (token == null) {
            // get from shared preferences
            url = url + "&token=" + AndroidacyRepoData.token
        }
        // Add device_id to url if not present
        var deviceId = uri!!.getQueryParameter("device_id")
        if (deviceId == null) {
            // get from shared preferences
            deviceId = AndroidacyRepoData.generateDeviceId()
            url = "$url&device_id=$deviceId"
        }
        // check if client_id is present
        var clientId = uri!!.getQueryParameter("client_id")
        if (clientId == null) {
            // get from shared preferences
            clientId = BuildConfig.ANDROIDACY_CLIENT_ID
            url = "$url&client_id=$clientId"
        }
        val allowInstall = intent.getBooleanExtra(Constants.EXTRA_ANDROIDACY_ALLOW_INSTALL, false)
        var title = intent.getStringExtra(Constants.EXTRA_ANDROIDACY_ACTIONBAR_TITLE)
        val config = intent.getStringExtra(Constants.EXTRA_ANDROIDACY_ACTIONBAR_CONFIG)
        val compatLevel = intent.getIntExtra(Constants.EXTRA_ANDROIDACY_COMPAT_LEVEL, 0)
        this.setContentView(R.layout.webview)
        setActionBarBackground(null)
        setDisplayHomeAsUpEnabled(true)
        if (title.isNullOrEmpty()) {
            title = "Androidacy"
        }
        if (allowInstall || title.isEmpty()) {
            hideActionBar()
        } else { // Only used for note section
            if (!config.isNullOrEmpty()) {
                val configPkg = IntentHelper.getPackageOfConfig(config)
                try {
                    checkConfigTargetExists(this, configPkg, config)
                    this.setActionBarExtraMenuButton(R.drawable.ic_baseline_app_settings_alt_24) { _: MenuItem? ->
                        IntentHelper.openConfig(this, config)
                        true
                    }
                } catch (ignored: PackageManager.NameNotFoundException) {
                }
            }
        }
        val prgInd = findViewById<LinearProgressIndicator>(R.id.progress_bar)
        prgInd.max = 100
        webView = findViewById(R.id.webView)
        val wbv = webView
        val webSettings = wbv?.settings
        webSettings?.userAgentString = androidacyUA
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webSettings?.domStorageEnabled = true
        webSettings?.javaScriptEnabled = true
        webSettings?.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings?.allowFileAccess = false
        webSettings?.allowContentAccess = false
        webSettings?.mediaPlaybackRequiresUserGesture = false
        // enable webview debugging on debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // if app is in dark mode, force dark mode on webview
        if (MainApplication.INSTANCE!!.isDarkTheme) {
            // for api 33, use setAlgorithmicDarkeningAllowed, for api 29-32 use setForceDark, for api 28 and below use setForceDarkStrategy
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings!!, true)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDark(webSettings!!, WebSettingsCompat.FORCE_DARK_ON)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                @Suppress("DEPRECATION")
                WebSettingsCompat.setForceDarkStrategy(
                    webSettings!!,
                    WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                )
            }
        }
        // Attempt at fixing CloudFlare captcha.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            val allowList: MutableSet<String> = HashSet()
            allowList.add("https://*.androidacy.com")
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webSettings!!, allowList)
        }
        // get swipe to refresh layout
        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_layout)
        wbv?.webViewClient = object : WebViewClientCompat() {
            private var pageUrl: String? = null
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Don't open non Androidacy urls inside WebView
                if (request.isForMainFrame && !AndroidacyUtil.isAndroidacyLink(request.url)) {
                    if (downloadMode || backOnResume) return true
                    // sanitize url
                    @Suppress("NAME_SHADOWING") var url = request.url.toString()
                    url = AndroidacyUtil.hideToken(url)
                    Timber.i("Exiting WebView %s", url)
                    IntentHelper.openUri(view.context, request.url.toString())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return if (megaIntercept(pageUrl, request.url.toString())) {
                    // Block request as Androidacy doesn't allow duplicate requests
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageUrl = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressIndicator?.visibility = View.INVISIBLE
                progressIndicator?.setProgressCompat(0, false)
            }

            private fun onReceivedError(url: String, errorCode: Int) {
                if (url.startsWith("https://production-api.androidacy.com/magisk/") || url.startsWith(
                        "https://staging-api.androidacy.com/magisk/"
                    ) || url == pageUrl && errorCode == 419 || errorCode == 429 || errorCode == 503
                ) {
                    Toast.makeText(this@AndroidacyActivity, "Too many requests!", Toast.LENGTH_LONG)
                        .show()
                    runOnUiThread { forceBackPressed() }
                } else if (url == pageUrl) {
                    postOnUiThread { webViewNote!!.visibility = View.VISIBLE }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                this.onReceivedError(failingUrl, errorCode)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceErrorCompat
            ) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
                    this.onReceivedError(request.url.toString(), error.errorCode)
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                super.onReceivedSslError(view, handler, error)
                // log the error and url of its request
                Timber.tag("JSLog").e(error.toString())
            }
        }
        // logic for swipe to refresh
        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = false
            // reload page
            wbv?.reload()
        }
        wbv?.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                getFoxActivity(webView).startActivityForResult(fileChooserParams.createIntent()) { code: Int, data: Intent? ->
                    filePathCallback.onReceiveValue(
                        FileChooserParams.parseResult(code, data)
                    )
                }
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (BuildConfig.DEBUG_HTTP) {
                    when (consoleMessage.messageLevel()) {
                        MessageLevel.TIP -> Timber.tag("JSLog").i(consoleMessage.message())
                        MessageLevel.LOG -> Timber.tag("JSLog").d(consoleMessage.message())
                        MessageLevel.WARNING -> Timber.tag("JSLog").w(consoleMessage.message())
                        MessageLevel.ERROR -> Timber.tag("JSLog").e(consoleMessage.message())
                        else -> Timber.tag("JSLog").v(consoleMessage.message())
                    }
                }
                return true
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (downloadMode) return
                if (newProgress != 100 && prgInd.visibility != View.VISIBLE) {
                    Timber.i("Progress: %d, showing progress bar", newProgress)
                    prgInd.visibility = View.VISIBLE
                }
                // if progress is greater than one, set indeterminate to false
                if (newProgress > 1) {
                    Timber.i("Progress: %d, setting indeterminate to false", newProgress)
                    prgInd.isIndeterminate = false
                }
                prgInd.setProgressCompat(newProgress, true)
                if (newProgress == 100 && prgInd.visibility != View.INVISIBLE) {
                    Timber.i("Progress: %d, hiding progress bar", newProgress)
                    prgInd.isIndeterminate = true
                    prgInd.visibility = View.GONE
                }
            }
        }
        wbv?.setDownloadListener(DownloadListener setDownloadListener@{ downloadUrl: String, _: String?, _: String?, _: String?, _: Long ->
            Timber.i("Downloadable URL: %s", downloadUrl)
            val pageUrl = wbv.url
            if (downloadMode && isDownloadUrl(downloadUrl)) {
                megaIntercept(pageUrl, downloadUrl)
            }
            Timber.i("Download mode is on")
            if (AndroidacyUtil.isAndroidacyLink(downloadUrl) && !backOnResume) {
                Timber.i("Androidacy link detected")
                val androidacyWebAPI = androidacyWebAPI
                if (androidacyWebAPI != null) {
                    if (!androidacyWebAPI.downloadMode) {
                        // Native module popup may cause download after consumed action
                        if (androidacyWebAPI.consumedAction) return@setDownloadListener
                        // Workaround Androidacy bug
                        val moduleId = moduleIdOfUrl(downloadUrl)
                        if (megaIntercept(wbv.url, downloadUrl)) {
                            Timber.i("megaIntercept failure 2. Forcing onBackPress")
                            // Block request as Androidacy doesn't allow duplicate requests
                            return@setDownloadListener
                        } else if (moduleId != null) {
                            // Download module
                            Timber.i("megaIntercept failure. Forcing onBackPress")
                            forceBackPressed()
                        }
                    }
                    androidacyWebAPI.consumedAction = true
                    androidacyWebAPI.downloadMode = false
                }
                backOnResume = true
                Timber.i("Exiting WebView %s", AndroidacyUtil.hideToken(downloadUrl))
                for (prefix in arrayOf<String>(
                    "https://production-api.androidacy.com/magisk/file//",
                    "https://staging-api.androidacy.com/magisk/file/"
                )) {
                    if (downloadUrl.startsWith(prefix)) {
                        return@setDownloadListener
                    }
                }
                IntentHelper.openCustomTab(this, downloadUrl)
            }
        })
        androidacyWebAPI = AndroidacyWebAPI(this, allowInstall)
        onWebViewInitialize(webView, allowInstall)
        wbv?.addJavascriptInterface(androidacyWebAPI!!, "mmm")
        if (compatLevel != 0) androidacyWebAPI!!.notifyCompatModeRaw(compatLevel)
        val headers = HashMap<String, String>()
        headers["Accept-Language"] = this.resources.configuration.locales.get(0).language
        // set layout to view
        wbv?.loadUrl(url, headers)
    }

    override fun onResume() {
        super.onResume()
        if (backOnResume) {
            backOnResume = false
            forceBackPressed()
        } else if (androidacyWebAPI != null) {
            androidacyWebAPI!!.consumedAction = false
        }
    }

    private fun moduleIdOfUrl(url: String): String? {
        for (prefix in arrayOf(
            "https://production-api.androidacy.com/magisk/file/",
            "https://staging-api.androidacy.com/magisk/file/",
            "https://production-api.androidacy.com/magisk/readme/",
            "https://staging-api.androidacy.com/magisk/readme/",
            "https://prodiuction-api.androidacy.com/magisk/info/",
            "https://staging-api.androidacy.com/magisk/info/"
        )) { // Make both staging and non staging act the same
            var i = url.indexOf('?', prefix.length)
            if (i == -1) i = url.length
            if (url.startsWith(prefix)) return url.substring(prefix.length, i)
        }
        if (isFileUrl(url)) {
            val i = url.indexOf("&module=")
            if (i != -1) {
                val j = url.indexOf('&', i + 1)
                return if (j == -1) {
                    url.substring(i + 8)
                } else {
                    url.substring(i + 8, j)
                }
            }
        }
        return null
    }

    private fun isFileUrl(url: String?): Boolean {
        if (url == null) return false
        for (prefix in arrayOf(
            "https://production-api.androidacy.com/magisk/file/",
            "https://staging-api.androidacy.com/magisk/file/"
        )) { // Make both staging and non staging act the same
            if (url.startsWith(prefix)) {
                Timber.i("File URL: %s", url)
                return true
            }

        }
        return false
    }

    private fun isDownloadUrl(url: String): Boolean {
        for (prefix in arrayOf(
            "https://production-api.androidacy.com/magisk/file/",
            "https://staging-api.androidacy.com/magisk/file/"
        )) { // Make both staging and non staging act the same
            if (url.startsWith(prefix)) {
                Timber.i("Download URL: %s", url)
                return true
            }
        }
        return false
    }

    private fun megaIntercept(pageUrl: String?, fileUrl: String?): Boolean {
        if (pageUrl == null || fileUrl == null) return false
        // ensure neither pageUrl nor fileUrl are going to cause a crash
        pageUrl.replace(" ", "%20")
        fileUrl.replace(" ", "%20")
        if (!isFileUrl(fileUrl)) {
            return false
        }
        val androidacyWebAPI = androidacyWebAPI
        val moduleId = AndroidacyUtil.getModuleId(fileUrl)
        if (moduleId == null) {
            Timber.i("No module id?")
            // Re-open the page
            webView!!.loadUrl(pageUrl + "&force_refresh=" + System.currentTimeMillis())
        }
        val checksum = AndroidacyUtil.getChecksumFromURL(fileUrl)
        val moduleTitle = AndroidacyUtil.getModuleTitle(fileUrl)
        androidacyWebAPI!!.openNativeModuleDialogRaw(
            fileUrl,
            moduleId,
            moduleTitle,
            checksum,
            androidacyWebAPI.canInstall()
        )
        return true
    }

    @Throws(IOException::class)
    fun downloadFileAsync(url: String?): Uri {
        downloadMode = true
        runOnUiThread {
            progressIndicator!!.isIndeterminate = false
            progressIndicator!!.visibility = View.VISIBLE
        }
        var module: ByteArray?
        try {
            module = doHttpGet(
                url!!, ({ downloaded: Int, total: Int, _: Boolean ->
                    progressIndicator!!.setProgressCompat(
                        downloaded * 100 / total, true
                    )

                } as Http.ProgressListener?)!!)
            FileOutputStream(moduleFile).use { fileOutputStream -> fileOutputStream.write(module) }
        } finally {
            module = null
            runOnUiThread { progressIndicator!!.visibility = View.INVISIBLE }
        }
        backOnResume = true
        downloadMode = false
        @Suppress("ktConcatNullable")
        return FileProvider.getUriForFile(this, this.packageName + ".file-provider", moduleFile!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (webView != null) {
            val parent = webView!!.parent as SwipeRefreshLayout
            parent.removeView(webView)
            webView!!.removeAllViews()
            webView!!.destroy() // fix memory leak
        }
        Timber.i("onDestroy for %s", this)
    }

    companion object {
        init {
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }
    }
}