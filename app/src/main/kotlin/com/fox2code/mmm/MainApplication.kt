/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationManagerCompat
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Configuration
import cat.ereza.customactivityoncrash.config.CaocConfig
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.utils.TimberUtils.configTimber
import com.fox2code.mmm.utils.io.FileUtils
import com.fox2code.mmm.utils.io.GMSProviderInstaller.Companion.installIfNeeded
import com.fox2code.mmm.utils.io.net.Http.Companion.getHttpClientWithCache
import com.fox2code.rosettax.LanguageSwitcher
import com.google.common.hash.Hashing
import com.topjohnwu.superuser.Shell
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import ly.count.android.sdk.Countly
import ly.count.android.sdk.CountlyConfig
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Random
import kotlin.math.abs


@Suppress("unused", "MemberVisibilityCanBePrivate")
class MainApplication : Application(), Configuration.Provider, ActivityLifecycleCallbacks {

    private var callbacksRegistered = false
    var isTainted = false

    var lastActivity: AppCompatActivity? = null

    var modulesHaveUpdates = false

    var updateModuleCount = 0

    var updateModules: List<String> = ArrayList()

    @StyleRes
    private var managerThemeResId = R.style.Theme_MagiskModuleManager
    private var markwonThemeContext: ContextThemeWrapper? = null

    var markwon: Markwon? = null
        get() {
            if (isCrashHandler) return null
            if (field != null) return field
            var contextThemeWrapper = markwonThemeContext
            if (contextThemeWrapper == null) {
                markwonThemeContext = ContextThemeWrapper(this, managerThemeResId)
                contextThemeWrapper = markwonThemeContext
            }
            field = Markwon.builder(contextThemeWrapper!!).usePlugin(HtmlPlugin.create()).usePlugin(
                ImagesPlugin.create().addSchemeHandler(
                    OkHttpNetworkSchemeHandler.create(
                        getHttpClientWithCache()!!
                    )
                )
            ).build()
            return field
        }
    private var existingKey: CharArray? = null
    private var makingNewKey = false
    private var isCrashHandler = false

    var localModules: HashMap<String, LocalModuleInfo> = HashMap()
    var repoModules: HashMap<String, RepoModule> = HashMap()

    init {
        check(!(INSTANCE != null && INSTANCE !== this)) { "Duplicate application instance!" }
        INSTANCE = this
    }

    // generates or retrieves a key for encrypted room databases
    @SuppressLint("ApplySharedPref")
    fun getKey(): CharArray {
        // check if existing key is available
        if (existingKey != null) {
            return existingKey!!
        }
        // use android keystore to generate a key
        val masterKey = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val sharedPreferences = EncryptedSharedPreferences.create(
            this,
            "dbKey",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val key = sharedPreferences.getString("dbKey", null)
        if (key != null) {
            existingKey = key.toCharArray()
            return existingKey!!
        }
        // generate a new key
        val newKey = CharArray(32)
        val random = SecureRandom()
        for (i in newKey.indices) {
            newKey[i] = (random.nextInt(26) + 97).toChar()
        }
        sharedPreferences.edit().putString("dbKey", String(newKey)).commit()
        existingKey = newKey
        return existingKey!!
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder().build()
    }

    fun updateTheme() {
        @StyleRes val themeResId: Int
        var theme: String?
        val monet = isMonetEnabled
        when (getPreferences("mmm")!!.getString("pref_theme", "system").also { theme = it }) {
            "system" -> themeResId =
                if (monet) R.style.Theme_MagiskModuleManager_Monet else R.style.Theme_MagiskModuleManager

            "dark" -> themeResId =
                if (monet) R.style.Theme_MagiskModuleManager_Monet_Dark else R.style.Theme_MagiskModuleManager_Dark

            "black" -> themeResId =
                if (monet) R.style.Theme_MagiskModuleManager_Monet_Black else R.style.Theme_MagiskModuleManager_Black

            "light" -> themeResId =
                if (monet) R.style.Theme_MagiskModuleManager_Monet_Light else R.style.Theme_MagiskModuleManager_Light

            "transparent_light" -> {
                if (monet) {
                    Timber.tag("MainApplication").w("Monet is not supported for transparent theme")
                }
                themeResId = R.style.Theme_MagiskModuleManager_Transparent_Light
            }

            else -> {
                Timber.w("Unknown theme id: %s", theme)
                themeResId =
                    if (monet) R.style.Theme_MagiskModuleManager_Monet else R.style.Theme_MagiskModuleManager
            }
        }
        setManagerThemeResId(themeResId)
    }

    @StyleRes
    fun getManagerThemeResId(): Int {
        return managerThemeResId
    }

    @SuppressLint("NonConstantResourceId")
    fun setManagerThemeResId(@StyleRes resId: Int) {
        managerThemeResId = resId
        if (markwonThemeContext != null) {
            markwonThemeContext!!.setTheme(resId)
        }
        markwon = null
    }

    val isLightTheme: Boolean
        get() = when (managerThemeResId) {
            R.style.Theme_MagiskModuleManager, R.style.Theme_MagiskModuleManager_Monet, R.style.Theme_MagiskModuleManager_Dark, R.style.Theme_MagiskModuleManager_Monet_Dark, R.style.Theme_MagiskModuleManager_Black, R.style.Theme_MagiskModuleManager_Monet_Black -> false

            else -> true
        }

    private val isSystemLightTheme: Boolean
        get() = (this.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    val isDarkTheme: Boolean
        get() = !this.isLightTheme

    override fun onCreate() {
        super.onCreate()
        CaocConfig.Builder.create()
            .backgroundMode(CaocConfig.BACKGROUND_MODE_SILENT) //default: CaocConfig.BACKGROUND_MODE_SHOW_CUSTOM
            .enabled(true) //default: true
            .trackActivities(true) //default: false
            .minTimeBetweenCrashesMs(2000)
            .restartActivity(MainActivity::class.java) //default: null (your app's launch activity)
            .errorActivity(CrashHandler::class.java) //default: null (default error activity)
            .apply()
        supportedLocales.addAll(
            listOf(
                "ar",
                "bs",
                "cs",
                "de",
                "es-rMX",
                "es",
                "el",
                "fr",
                "hu",
                "id",
                "it",
                "ja",
                "nl",
                "pl",
                "pt",
                "pt-rBR",
                "ru",
                "tr",
                "uk",
                "vi",
                "zh",
                "zh-rTW",
                "en"
            )
        )
        if (INSTANCE == null) INSTANCE = this
        relPackageName = this.packageName
        var output = Shell.cmd("echo $(id -u)").exec().out[0]
        if (output != null) {
            output = output.trim { it <= ' ' }
            if (forceDebugLogging) Timber.d("UID: %s", output)
            if (output == "0") {
                if (forceDebugLogging) Timber.d("Root access granted")
            } else {
                if (forceDebugLogging) Timber.d(
                    "Root access or we're not uid 0. Current uid: %s",
                    output
                )
            }
        }
        if (!callbacksRegistered) {
            try {
                registerActivityLifecycleCallbacks(this)
                callbacksRegistered = true
            } catch (e: Exception) {
                Timber.e(e, "Failed to register activity lifecycle callbacks")
            }
        }
        // Initialize Timber
        configTimber()
        if (forceDebugLogging) Timber.i(
            "Starting AMM version %s (%d) - commit %s",
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            BuildConfig.COMMIT_HASH
        )
        // Update SSL Ciphers if update is possible
        installIfNeeded(this)
        // get intent. if isCrashing is not present or false, call FileUtils.ensureCacheDirs and FileUtils.ensureURLHandler
        isCrashHandler = intent!!.getBooleanExtra("isCrashing", false)
        if (!isCrashHandler) {
            val fileUtils = FileUtils()
            fileUtils.ensureCacheDirs()
            fileUtils.ensureURLHandler(this)
        }
        if (forceDebugLogging) Timber.d("Initializing AMM")
        if (forceDebugLogging) Timber.d("Started from background: %s", !isInForeground)
        if (forceDebugLogging) Timber.d("AMM is running in debug mode")
        // analytics
        if (forceDebugLogging) Timber.d("Initializing countly")
        if (!analyticsAllowed()) {
            if (forceDebugLogging) Timber.d("countly is not allowed")
        } else {
            val config = CountlyConfig(
                this,
                "ff1dc022295f64a7a5f6a5ca07c0294400c71b60",
                "https://ctly.androidacy.com"
            )
            if (isCrashReportingEnabled) {
                config.enableCrashReporting()
            }
            config.enableAutomaticViewTracking()
            config.setPushIntentAddMetadata(true)
            config.setLoggingEnabled(BuildConfig.DEBUG)
            config.setRequiresConsent(false)
            config.setRecordAppStartTime(true)
            Countly.sharedInstance().init(config)
            Countly.applicationOnCreate()
        }
        try {
            @Suppress("DEPRECATION") @SuppressLint("PackageManagerGetSignatures") val s =
                this.packageManager.getPackageInfo(
                    this.packageName, PackageManager.GET_SIGNATURES
                ).signatures
            val osh = arrayOf(
                "7bec7c4462f4aac616612d9f56a023ee3046e83afa956463b5fab547fd0a0be6",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "e8ce7deca880304d7ff09f8fc37656cfa927cee7f6a0bb7b3feda6a5942931f5",
                "339af2fb5b671fa4af6436b585351f2f1fc746d1d922f9a0b01df2d576381015"
            )
            val oosh = Hashing.sha256().hashBytes(s[0].toByteArray()).toString()
            o = listOf(*osh).contains(oosh)
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        val sharedPreferences = getPreferences("mmm")
        // We are only one process so it's ok to do this
        val bootPrefs = getPreferences("mmm_boot")
        val lastBoot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val lastBootPrefs = bootPrefs!!.getLong("last_boot", 0)
        isFirstBoot = if (lastBootPrefs == 0L || abs(lastBoot - lastBootPrefs) > 100) {
            val firstBoot = sharedPreferences!!.getBoolean("first_boot", true)
            bootPrefs.edit().clear().putLong("last_boot", lastBoot)
                .putBoolean("first_boot", firstBoot).apply()
            if (firstBoot) {
                sharedPreferences.edit().putBoolean("first_boot", false).apply()
            }
            firstBoot
        } else {
            bootPrefs.getBoolean("first_boot", false)
        }
        // Force initialize language early.
        LanguageSwitcher(this)
        updateTheme()
        if (!BuildConfig.DEBUG && isDeveloper) {
            Timber.e("Developer mode is enabled! Support will not be provided if you are not a developer!")
            isTainted = true
        }
        // Update emoji config
        val fontRequestEmojiCompatConfig = DefaultEmojiCompatConfig.create(this)
        if (fontRequestEmojiCompatConfig != null) {
            fontRequestEmojiCompatConfig.setReplaceAll(true)
            fontRequestEmojiCompatConfig.setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
            val emojiCompat = EmojiCompat.init(fontRequestEmojiCompatConfig)
            Thread({
                if (forceDebugLogging) Timber.i("Loading emoji compat...")
                emojiCompat.load()
                if (forceDebugLogging) Timber.i("Emoji compat loaded!")
            }, "Emoji compat init.").start()
        }
        @Suppress("KotlinConstantConditions") if ((BuildConfig.ANDROIDACY_CLIENT_ID == "")) {
            Timber.w("Androidacy client id is empty! Please set it in androidacy.properties. Will not enable Androidacy.")
            val editor = sharedPreferences!!.edit()
            editor.putBoolean("pref_androidacy_repo_enabled", false)
            Timber.w("ANDROIDACY_CLIENT_ID is empty, disabling AndroidacyRepoData 1")
            editor.apply()
        }
    }

    private val intent: Intent?
        get() = this.packageManager.getLaunchIntentForPackage(this.packageName)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val newTimeFormatLocale = newConfig.locales[0]
        if (timeFormatLocale !== newTimeFormatLocale) {
            timeFormatLocale = newTimeFormatLocale
            timeFormat = SimpleDateFormat(TFS, timeFormatLocale)
        }
        super.onConfigurationChanged(newConfig)
    }

    // getDataDir wrapper with optional path parameter
    @Suppress("NAME_SHADOWING")
    fun getDataDirWithPath(path: String?): File {
        var path = path
        var dataDir = this.dataDir
        // for path with / somewhere in the middle, its a subdirectory
        return if (path != null) {
            if (path.startsWith("/")) path = path.substring(1)
            if (path.endsWith("/")) path = path.substring(0, path.length - 1)
            if (path.contains("/")) {
                val dirs = path.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                for (dir: String? in dirs) {
                    dataDir = File(dataDir, dir!!)
                    // make sure the directory exists
                    if (!dataDir.exists()) {
                        if (!dataDir.mkdirs()) {
                            if (forceDebugLogging) Timber.w(
                                "Failed to create directory %s", dataDir
                            )
                        }
                    }
                }
            } else {
                dataDir = File(dataDir, path)
                // create the directory if it doesn't exist
                if (!dataDir.exists()) {
                    if (!dataDir.mkdirs()) {
                        if (forceDebugLogging) Timber.w("Failed to create directory %s", dataDir)
                    }
                }
            }
            dataDir
        } else {
            throw IllegalArgumentException("Path cannot be null")
        }
    }

    @SuppressLint("RestrictedApi") // view is nullable because it's called from xml
    fun resetApp() {
        // cant show a dialog because android is throwing a fit so here's hoping anybody who calls this method is otherwise confirming that the user wants to reset the app
        Timber.w("Resetting app...")
        // recursively delete the app's data
        (this.getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
    }

    // determine if the app is in the foreground
    val isInForeground: Boolean
        get() {
            // determine if the app is in the foreground
            val activityManager = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            if (appProcesses == null) {
                if (forceDebugLogging) Timber.d("appProcesses is null")
                return false
            }
            val packageName = this.packageName
            for (appProcess in appProcesses) {
                if (forceDebugLogging) Timber.d(
                    "Process: %s, Importance: %d", appProcess.processName, appProcess.importance
                )
                if (appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND && appProcess.processName == packageName) {
                    return true
                }
            }
            return false
        }

    // returns if background execution is restricted
    val isBackgroundRestricted: Boolean
        get() {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.isBackgroundRestricted
            } else {
                false
            }
        }// sleep until the key is made


    fun resetUpdateModule() {
        modulesHaveUpdates = false
        updateModuleCount = 0
        updateModules = ArrayList()
    }

    class ReleaseTree : Timber.Tree() {
        @SuppressLint("LogNotTimber")
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // basically silently drop all logs below error, and write the rest to logcat
            @Suppress("NAME_SHADOWING") var message = message
            if (INSTANCE!!.isTainted) {
                // prepend [TAINTED] to the message
                message = "[TAINTED] $message"
            }
            if (forceDebugLogging) {
                if (priority >= Log.DEBUG) {
                    when (priority) {
                        Log.DEBUG -> Log.d(tag, message, t)
                        Log.INFO -> Log.i(tag, message, t)
                        Log.WARN -> Log.w(tag, message, t)
                        Log.ERROR -> Log.e(tag, message, t)
                        Log.ASSERT -> Log.wtf(tag, message, t)
                    }
                    t?.printStackTrace()
                }
            } else {
                if (priority >= Log.ERROR) {
                    Log.e(tag, message, t)
                }
            }
        }
    }

    companion object {

        var forceDebugLogging: Boolean =
            BuildConfig.DEBUG || getPreferences("mmm")?.getBoolean(
                "pref_force_debug_logging",
                false
            ) ?: false

        // Warning! Locales that don't exist will crash the app
        // Anything that is commented out is supported but the translation is not complete to at least 60%
        @JvmField
        val supportedLocales = HashSet<String>()
        private const val TFS = "dd MMM yyyy" // Example: 13 july 2001
        private var shellBuilder: Shell.Builder? = null

        // Is application wrapped, and therefore must reduce it's feature set.
        @SuppressLint("RestrictedApi") // Use FoxProcess wrapper helper.
        const val isWrapped = false
        private val callers = ArrayList<String>()

        @JvmField
        var o = false
        private var SHOWCASE_MODE_TRUE: String? = null
        private var sc: Long = 0
        private var timeFormatLocale = Resources.getSystem().configuration.locales[0]
        private var timeFormat = SimpleDateFormat(TFS, timeFormatLocale)
        private var relPackageName = BuildConfig.APPLICATION_ID

        @SuppressLint("StaticFieldLeak")
        var INSTANCE: MainApplication? = null
            private set
            get() {
                if (field == null) {
                    Timber.w("MainApplication.INSTANCE is null, using fallback!")
                    return null
                }
                return field
            }

        var isFirstBoot = false
        private var mSharedPrefs: HashMap<Any, Any>? = null
        var updateCheckBg: String? = null

        init {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER).setTimeout(15)
            )
            // set verbose logging for debug builds
            if (BuildConfig.DEBUG) {
                Shell.enableVerboseLogging = true
            }
            // prewarm shell
            Shell.getShell {
                if (forceDebugLogging) Timber.d("Shell prewarmed")
            }
            val random = Random()
            do {
                sc = random.nextLong()
            } while (sc == 0L)
        }

        fun build(vararg command: String?): Shell {
            return shellBuilder!!.build(*command)
        }

        fun addSecret(intent: Intent) {
            val componentName = intent.component
            val packageName = componentName?.packageName ?: (intent.getPackage())!!
            require(
                !(!BuildConfig.APPLICATION_ID.equals(
                    packageName, ignoreCase = true
                ) && relPackageName != packageName)
            ) {
                // Code safeguard, we should never reach here.
                "Can't add secret to outbound Intent"
            }
            intent.putExtra("secret", sc)
        }

        @Suppress("NAME_SHADOWING")
        fun getPreferences(name: String): SharedPreferences? {
            // encryptedSharedPreferences is used
            return try {
            var name = name
            val mContext: Context? = INSTANCE!!.applicationContext
            name += "x"
            if (mSharedPrefs == null) {
                mSharedPrefs = HashMap()
            }
            if (mSharedPrefs!!.containsKey(name)) {
                return mSharedPrefs!![name] as SharedPreferences?
            }
                val masterKey =
                    MasterKey.Builder(mContext!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                val sharedPreferences = EncryptedSharedPreferences.create(
                    mContext,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                mSharedPrefs!![name] = sharedPreferences
                sharedPreferences
            } catch (e: Exception) {
                // try again five times, with a 250ms delay between each try. if we still can't get the shared preferences, throw an exception
                var i = 0
                while (i < 5) {
                    try {
                        Thread.sleep(250)
                    } catch (ignored: InterruptedException) {
                    }
                    try {
                        val masterKey =
                            MasterKey.Builder(INSTANCE!!.applicationContext)
                                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                .build()
                        val sharedPreferences = EncryptedSharedPreferences.create(
                            INSTANCE!!.applicationContext,
                            name,
                            masterKey,
                            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                        mSharedPrefs!![name] = sharedPreferences
                        return sharedPreferences
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to get shared preferences")
                    }
                    i++
                }
                return null
            }
        }

        fun clearCachedSharedPrefs() {
            mSharedPrefs = null
        }

        fun checkSecret(intent: Intent?): Boolean {
            return intent != null && intent.getLongExtra("secret", sc.inv()) == sc
        }

        // convert from String to boolean
        val isShowcaseMode: Boolean
            get() {
                if (SHOWCASE_MODE_TRUE != null) {
                    // convert from String to boolean
                    return java.lang.Boolean.parseBoolean(SHOWCASE_MODE_TRUE)
                }
                val showcaseMode =
                    getPreferences("mmm")!!.getBoolean("pref_showcase_mode", false)
                SHOWCASE_MODE_TRUE = showcaseMode.toString()
                return showcaseMode
            }

        fun shouldPreventReboot(): Boolean {
            return getPreferences("mmm")!!.getBoolean("pref_prevent_reboot", true)
        }

        val isShowIncompatibleModules: Boolean
            get() = getPreferences("mmm")!!.getBoolean("pref_show_incompatible", false)
        val isForceDarkTerminal: Boolean
            get() = getPreferences("mmm")!!.getBoolean("pref_force_dark_terminal", false)
        val isTextWrapEnabled: Boolean
            get() = getPreferences("mmm")!!.getBoolean("pref_wrap_text", false)
        val isDohEnabled: Boolean
            get() = getPreferences("mmm")!!.getBoolean("pref_dns_over_https", true)
        val isMonetEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getPreferences("mmm")!!.getBoolean(
                "pref_enable_monet", true
            )
        val isBlurEnabled: Boolean
            get() = getPreferences("mmm")!!.getBoolean("pref_enable_blur", false)

        val isDeveloper: Boolean
            get() {
                return if (BuildConfig.DEBUG) true else getPreferences("mmm")!!.getBoolean(
                    "developer", false
                )
            }
        val isDisableLowQualityModuleFilter: Boolean
            get() = getPreferences("mmm")!!.getBoolean(
                "pref_disable_low_quality_module_filter", false
            ) && isDeveloper
        val isUsingMagiskCommand: Boolean
            get() = (peekMagiskVersion() >= Constants.MAGISK_VER_CODE_INSTALL_COMMAND) && getPreferences(
                "mmm"
            )!!.getBoolean(
                "pref_use_magisk_install_command",
                false
            ) && isDeveloper && !InstallerInitializer.isKsu

        val isBackgroundUpdateCheckEnabled: Boolean
            get() {
                if (updateCheckBg != null) {
                    return java.lang.Boolean.parseBoolean(updateCheckBg)
                }
                val wrapped = isWrapped
                val updateCheckBgTemp = !wrapped && getPreferences("mmm")!!.getBoolean(
                    "pref_background_update_check", true
                )
                updateCheckBg = updateCheckBgTemp.toString()
                return java.lang.Boolean.parseBoolean(updateCheckBg)
            }
        val isAndroidacyTestMode: Boolean
            get() = isDeveloper && getPreferences("mmm")!!.getBoolean(
                "pref_androidacy_test_mode", false
            )

        fun setHasGottenRootAccess(bool: Boolean) {
            getPreferences("mmm")!!.edit().putBoolean("has_root_access", bool).apply()
        }

        val isCrashReportingEnabled: Boolean
            get() = analyticsAllowed() && getPreferences("mmm")!!.getBoolean(
                "pref_crash_reporting", BuildConfig.DEFAULT_ENABLE_CRASH_REPORTING
            )
        val bootSharedPreferences: SharedPreferences?
            get() = getPreferences("mmm_boot")

        fun formatTime(timeStamp: Long): String {
            // new Date(x) also get the local timestamp for format
            return timeFormat.format(Date(timeStamp))
        }

        val isNotificationPermissionGranted: Boolean
            get() = NotificationManagerCompat.from((INSTANCE)!!).areNotificationsEnabled()

        fun analyticsAllowed(): Boolean {
            return getPreferences("mmm")!!.getBoolean(
                "pref_analytics_enabled", BuildConfig.DEFAULT_ENABLE_ANALYTICS
            )
        }

        fun shouldShowFeedback(): Boolean {
            // should not have been shown in 14 days and only 1 in 5 chance
            if (!analyticsAllowed()) {
                return false
            }
            val randChance = Random().nextInt(5)
            val lastShown = getPreferences("mmm")!!.getLong("last_feedback", 0)
            if (forceDebugLogging) Timber.d(
                "Last feedback shown: %d, randChance: %d",
                lastShown,
                randChance
            )
            return System.currentTimeMillis() - lastShown > 1209600000 && randChance == 0
        }

        var dirty = false
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        lastActivity = activity as AppCompatActivity
        activity.setTheme(managerThemeResId)
    }

    override fun onActivityStarted(activity: Activity) {
        if (analyticsAllowed()) Countly.sharedInstance().onStart(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        lastActivity = activity as AppCompatActivity
        activity.setTheme(managerThemeResId)
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
        if (analyticsAllowed()) Countly.sharedInstance().onStop()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }
}
