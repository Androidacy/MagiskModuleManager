package com.fox2code.mmm

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.annotation.StyleRes
import androidx.core.app.NotificationManagerCompat
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Configuration
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.app.FoxApplication
import com.fox2code.foxcompat.app.internal.FoxProcessExt
import com.fox2code.foxcompat.view.FoxThemeWrapper
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.utils.TimberUtils.configTimber
import com.fox2code.mmm.utils.io.FileUtils
import com.fox2code.mmm.utils.io.GMSProviderInstaller.Companion.installIfNeeded
import com.fox2code.mmm.utils.io.net.Http.Companion.getHttpClientWithCache
import com.fox2code.mmm.utils.sentry.SentryMain
import com.fox2code.mmm.utils.sentry.SentryMain.initialize
import com.fox2code.rosettax.LanguageSwitcher
import com.google.common.hash.Hashing
import com.topjohnwu.superuser.Shell
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.network.OkHttpNetworkSchemeHandler
import io.realm.Realm
import org.matomo.sdk.Matomo
import org.matomo.sdk.Tracker
import org.matomo.sdk.TrackerBuilder
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Random
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.math.abs
import kotlin.math.max

@Suppress("unused", "MemberVisibilityCanBePrivate")
class MainApplication : FoxApplication(), Configuration.Provider {

    @JvmField
    var modulesHaveUpdates = false
    @JvmField
    var updateModuleCount = 0
    @JvmField
    var updateModules: List<String> = ArrayList()

    @StyleRes
    private var managerThemeResId = R.style.Theme_MagiskModuleManager
    private var markwonThemeContext: FoxThemeWrapper? = null
    @JvmField
    var markwon: Markwon? = null
    private var existingKey: ByteArray? = null
    @JvmField
    var tracker: Tracker? = null
    private var makingNewKey = false
    private var isCrashHandler = false

    init {
        check(!(INSTANCE != null && INSTANCE !== this)) { "Duplicate application instance!" }
        INSTANCE = this
    }

    fun getMarkwon(): Markwon? {
        if (isCrashHandler) return null
        if (markwon != null) return markwon
        var contextThemeWrapper = markwonThemeContext
        if (contextThemeWrapper == null) {
            markwonThemeContext = FoxThemeWrapper(this, managerThemeResId)
            contextThemeWrapper = markwonThemeContext
        }
        val markwon =
            Markwon.builder(contextThemeWrapper!!).usePlugin(HtmlPlugin.create()).usePlugin(
                ImagesPlugin.create().addSchemeHandler(
                    OkHttpNetworkSchemeHandler.create(
                        getHttpClientWithCache()!!
                    )
                )
            ).build()
        return markwon.also { this.markwon = it }
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder().build()
    }

    fun updateTheme() {
        @StyleRes val themeResId: Int
        var theme: String?
        val monet = isMonetEnabled
        when (getSharedPreferences("mmm")!!.getString("pref_theme", "system").also { theme = it }) {
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

    @SuppressLint("NonConstantResourceId")
    override fun isLightTheme(): Boolean {
        return when (getSharedPreferences("mmm")!!
            .getString("pref_theme", "system")) {
            "system" -> isSystemLightTheme
            "dark", "black" -> false
            else -> true
        }
    }

    private val isSystemLightTheme: Boolean
        get() = (this.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
    val isDarkTheme: Boolean
        get() = !this.isLightTheme

    @Synchronized
    fun getTracker(): Tracker? {
        if (tracker == null) {
            tracker = TrackerBuilder.createDefault(BuildConfig.ANALYTICS_ENDPOINT, 1)
                .build(Matomo.getInstance(this))
            val tracker = tracker!!
            tracker.startNewSession()
            tracker.dispatchInterval = 1000
        }
        return tracker
    }

    override fun onCreate() {
        supportedLocales.addAll(
            listOf(
                "ar",
                "bs",
                "cs",
                "de",
                "es-rMX",
                "fr",
                "hu",
                "id",
                "ja",
                "hu",
                "nl",
                "pl",
                "pt",
                "pt-rBR",
                "ro",
                "ru",
                "tr",
                "uk",
                "zh",
                "zh-rTW",
                "en"
            )
        )
        if (INSTANCE == null) INSTANCE = this
        relPackageName = this.packageName
        super.onCreate()
        initialize(this)
        // Initialize Timber
        configTimber()
        Timber.i(
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
        Timber.d("Initializing AMM")
        Timber.d("Started from background: %s", !isInForeground)
        Timber.d("AMM is running in debug mode")
        Timber.d("Initializing Realm")
        Realm.init(this)
        Timber.d("Initialized Realm")
        // analytics
        Timber.d("Initializing matomo")
        getTracker()
        if (!isMatomoAllowed()) {
            Timber.d("Matomo is not allowed")
            tracker!!.isOptOut = true
        } else {
            tracker!!.isOptOut = false
        }
        if (getSharedPreferences("matomo")!!
                .getBoolean("install_tracked", false)
        ) {
            TrackHelper.track().download().with(INSTANCE!!.getTracker())
            Timber.d("Sent install event to matomo")
            getSharedPreferences("matomo")!!
                .edit().putBoolean("install_tracked", true).apply()
        } else {
            Timber.d("Matomo already has install")
        }
        try {
            @Suppress("DEPRECATION")
            @SuppressLint("PackageManagerGetSignatures") val s = this.packageManager.getPackageInfo(
                this.packageName,
                PackageManager.GET_SIGNATURES
            ).signatures
            @Suppress("SpellCheckingInspection") val osh = arrayOf(
                "7bec7c4462f4aac616612d9f56a023ee3046e83afa956463b5fab547fd0a0be6",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "e8ce7deca880304d7ff09f8fc37656cfa927cee7f6a0bb7b3feda6a5942931f5",
                "339af2fb5b671fa4af6436b585351f2f1fc746d1d922f9a0b01df2d576381015"
            )
            val oosh = Hashing.sha256().hashBytes(s[0].toByteArray()).toString()
            o = listOf(*osh).contains(oosh)
        } catch (ignored: PackageManager.NameNotFoundException) {
        }
        val sharedPreferences = getSharedPreferences("mmm")
        // We are only one process so it's ok to do this
        val bootPrefs = getSharedPreferences("mmm_boot")
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
        // Update emoji config
        val fontRequestEmojiCompatConfig = DefaultEmojiCompatConfig.create(this)
        if (fontRequestEmojiCompatConfig != null) {
            fontRequestEmojiCompatConfig.setReplaceAll(true)
            fontRequestEmojiCompatConfig.setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
            val emojiCompat = EmojiCompat.init(fontRequestEmojiCompatConfig)
            Thread({
                Timber.i("Loading emoji compat...")
                emojiCompat.load()
                Timber.i("Emoji compat loaded!")
            }, "Emoji compat init.").start()
        }
        @Suppress("KotlinConstantConditions")
        if ((BuildConfig.ANDROIDACY_CLIENT_ID == "")) {
            Timber.w("Androidacy client id is empty! Please set it in androidacy.properties. Will not enable Androidacy.")
            val editor = sharedPreferences!!.edit()
            editor.putBoolean("pref_androidacy_repo_enabled", false)
            Timber.w("ANDROIDACY_CLIENT_ID is empty, disabling AndroidacyRepoData 1")
            editor.apply()
        }
    }

    private val intent: Intent?
        get() = this.packageManager.getLaunchIntentForPackage(this.packageName)

    override fun onCreateFoxActivity(compatActivity: FoxActivity) {
        super.onCreateFoxActivity(compatActivity)
        compatActivity.setTheme(managerThemeResId)
    }

    override fun onRefreshUI(compatActivity: FoxActivity) {
        super.onRefreshUI(compatActivity)
        compatActivity.setThemeRecreate(managerThemeResId)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val newTimeFormatLocale = newConfig.locales[0]
        if (timeFormatLocale !== newTimeFormatLocale) {
            timeFormatLocale = newTimeFormatLocale
            timeFormat = SimpleDateFormat(timeFormatString, timeFormatLocale)
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
                            if (BuildConfig.DEBUG) Timber.w(
                                "Failed to create directory %s",
                                dataDir
                            )
                        }
                    }
                }
            } else {
                dataDir = File(dataDir, path)
                // create the directory if it doesn't exist
                if (!dataDir.exists()) {
                    if (!dataDir.mkdirs()) {
                        if (BuildConfig.DEBUG) Timber.w("Failed to create directory %s", dataDir)
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
                Timber.d("appProcesses is null")
                return false
            }
            val packageName = this.packageName
            for (appProcess in appProcesses) {
                Timber.d(
                    "Process: %s, Importance: %d",
                    appProcess.processName,
                    appProcess.importance
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

    // attempt to read the existingKey property
    // check if we have a key already
    // open a connection to the android keystore
    // create a securely generated random asymmetric RSA key
    // create a cipher that uses AES encryption -- we'll use this to encrypt our key
    // generate secret key
    // access the generated key in the android keystore, then
    // use the cipher to create an encrypted version of the key
    // keep the encrypted key in shared preferences
    // to persist it across application runs
    // pass to a realm configuration via encryptionKey()
    // Create a key to encrypt a realm and save it securely in the keystore
    val key: ByteArray
        get() {
            if (makingNewKey) {
                // sleep until the key is made
                while (makingNewKey) try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            // attempt to read the existingKey property
            if (existingKey != null && existingKey!!.isNotEmpty()) {
                return existingKey as ByteArray
            }
            // check if we have a key already
            val sharedPreferences = getSharedPreferences("realm_key")
            makingNewKey = if (sharedPreferences!!.contains("iv_and_encrypted_key")) {
                return getExistingKey()
            } else {
                true
            }
            // open a connection to the android keystore
            val keyStore: KeyStore
            try {
                keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
            } catch (e: KeyStoreException) {
                Timber.v("Failed to open the keystore.")
                throw RuntimeException(e)
            } catch (e: NoSuchAlgorithmException) {
                Timber.v("Failed to open the keystore.")
                throw RuntimeException(e)
            } catch (e: CertificateException) {
                Timber.v("Failed to open the keystore.")
                throw RuntimeException(e)
            } catch (e: IOException) {
                Timber.v("Failed to open the keystore.")
                throw RuntimeException(e)
            }
            // create a securely generated random asymmetric RSA key
            val realmKey = ByteArray(Realm.ENCRYPTION_KEY_LENGTH)
            do {
                SecureRandom().nextBytes(realmKey)
            } while (realmKey[0].toInt() == 0)
            // create a cipher that uses AES encryption -- we'll use this to encrypt our key
            val cipher: Cipher = try {
                Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_CBC + "/" + KeyProperties.ENCRYPTION_PADDING_PKCS7)
            } catch (e: NoSuchAlgorithmException) {
                Timber.e("Failed to create a cipher.")
                throw RuntimeException(e)
            } catch (e: NoSuchPaddingException) {
                Timber.e("Failed to create a cipher.")
                throw RuntimeException(e)
            }
            Timber.v("Cipher created.")
            // generate secret key
            val keyGenerator: KeyGenerator = try {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            } catch (e: NoSuchAlgorithmException) {
                Timber.e("Failed to access the key generator.")
                throw RuntimeException(e)
            } catch (e: NoSuchProviderException) {
                Timber.e("Failed to access the key generator.")
                throw RuntimeException(e)
            }
            val keySpec = KeyGenParameterSpec.Builder(
                "realm_key",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7).build()
            try {
                keyGenerator.init(keySpec)
            } catch (e: InvalidAlgorithmParameterException) {
                Timber.e("Failed to generate a secret key.")
                throw RuntimeException(e)
            }
            Timber.v("Secret key generated.")
            keyGenerator.generateKey()
            Timber.v("Secret key stored in the keystore.")
            // access the generated key in the android keystore, then
            // use the cipher to create an encrypted version of the key
            val initializationVector: ByteArray
            val encryptedKeyForRealm: ByteArray
            try {
                val secretKey = keyStore.getKey("realm_key", null) as SecretKey
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                encryptedKeyForRealm = cipher.doFinal(realmKey)
                initializationVector = cipher.iv
            } catch (e: InvalidKeyException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            } catch (e: UnrecoverableKeyException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            } catch (e: NoSuchAlgorithmException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            } catch (e: KeyStoreException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            } catch (e: BadPaddingException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            } catch (e: IllegalBlockSizeException) {
                Timber.e("Failed encrypting the key with the secret key.")
                throw RuntimeException(e)
            }
            // keep the encrypted key in shared preferences
            // to persist it across application runs
            val initializationVectorAndEncryptedKey =
                ByteArray(Integer.BYTES + initializationVector.size + encryptedKeyForRealm.size)
            val buffer = ByteBuffer.wrap(initializationVectorAndEncryptedKey)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(initializationVector.size)
            buffer.put(initializationVector)
            buffer.put(encryptedKeyForRealm)
            Timber.d("Created all keys successfully.")
            getSharedPreferences("realm_key")!!
                .edit().putString(
                    "iv_and_encrypted_key",
                    Base64.encodeToString(initializationVectorAndEncryptedKey, Base64.NO_WRAP)
                ).apply()
            Timber.d("Saved the encrypted key in shared preferences.")
            makingNewKey = false
            Timber.d("Returning the new key. Key length: %d", realmKey.size)
            return realmKey // pass to a realm configuration via encryptionKey()
        }

    // Access the encrypted key in the keystore, decrypt it with the secret,
    // and use it to open and read from the realm again
    fun getExistingKey(): ByteArray {
        Timber.v("Getting existing key.")
        // attempt to read the existingKey property
        if (existingKey != null) {
            Timber.v("Existing key found in memory.")
            return existingKey as ByteArray
        }
        // open a connection to the android keystore
        val keyStore: KeyStore
        try {
            keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
        } catch (e: KeyStoreException) {
            Timber.e("Failed to open the keystore.")
            throw RuntimeException(e)
        } catch (e: NoSuchAlgorithmException) {
            Timber.e("Failed to open the keystore.")
            throw RuntimeException(e)
        } catch (e: CertificateException) {
            Timber.e("Failed to open the keystore.")
            throw RuntimeException(e)
        } catch (e: IOException) {
            Timber.e("Failed to open the keystore.")
            throw RuntimeException(e)
        }
        Timber.v("Keystore opened.")
        // access the encrypted key that's stored in shared preferences
        val initializationVectorAndEncryptedKey = Base64.decode(
            getSharedPreferences("realm_key")!!
                .getString("iv_and_encrypted_key", null), Base64.DEFAULT
        )
        Timber.d(
            "Retrieved the encrypted key from shared preferences. Key length: %d",
            initializationVectorAndEncryptedKey.size
        )
        val buffer = ByteBuffer.wrap(initializationVectorAndEncryptedKey)
        buffer.order(ByteOrder.BIG_ENDIAN)
        // extract the length of the initialization vector from the buffer
        val initializationVectorLength = buffer.int
        // extract the initialization vector based on that length
        val initializationVector = ByteArray(initializationVectorLength)
        buffer[initializationVector]
        // extract the encrypted key
        val encryptedKey =
            ByteArray(initializationVectorAndEncryptedKey.size - Integer.BYTES - initializationVectorLength)
        buffer[encryptedKey]
        Timber.d("Got key from shared preferences.")
        // create a cipher that uses AES encryption to decrypt our key
        val cipher: Cipher = try {
            Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_CBC + "/" + KeyProperties.ENCRYPTION_PADDING_PKCS7)
        } catch (e: NoSuchAlgorithmException) {
            Timber.e("Failed to create cipher.")
            throw RuntimeException(e)
        } catch (e: NoSuchPaddingException) {
            Timber.e("Failed to create cipher.")
            throw RuntimeException(e)
        }
        // decrypt the encrypted key with the secret key stored in the keystore
        val decryptedKey: ByteArray = try {
            val secretKey = keyStore.getKey("realm_key", null) as SecretKey
            val initializationVectorSpec = IvParameterSpec(initializationVector)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, initializationVectorSpec)
            cipher.doFinal(encryptedKey)
        } catch (e: InvalidKeyException) {
            Timber.e("Failed to decrypt. Invalid key.")
            throw RuntimeException(e)
        } catch (e: UnrecoverableKeyException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        } catch (e: NoSuchAlgorithmException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        } catch (e: BadPaddingException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        } catch (e: KeyStoreException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        } catch (e: IllegalBlockSizeException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        } catch (e: InvalidAlgorithmParameterException) {
            Timber.e("Failed to decrypt the encrypted realm key with the secret key.")
            throw RuntimeException(e)
        }
        // set property on MainApplication to indicate that the key has been accessed
        existingKey = decryptedKey
        return decryptedKey // pass to a realm configuration via encryptionKey()
    }

    fun resetUpdateModule() {
        modulesHaveUpdates = false
        updateModuleCount = 0
        updateModules = ArrayList()
    }

    class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            // basically silently drop all logs below error, and write the rest to logcat
            if (priority >= Log.ERROR) {
                if (t != null) {
                    Log.println(priority, tag, message)
                    t.printStackTrace()
                } else {
                    Log.println(priority, tag, message)
                }
            }
        }
    }

    companion object {

        // Warning! Locales that don't exist will crash the app
        // Anything that is commented out is supported but the translation is not complete to at least 60%
        @JvmField
        val supportedLocales = HashSet<String>()
        private const val timeFormatString = "dd MMM yyyy" // Example: 13 july 2001
        private var shellBuilder: Shell.Builder? = null

        // Is application wrapped, and therefore must reduce it's feature set.
        @SuppressLint("RestrictedApi") // Use FoxProcess wrapper helper.
        @JvmField
        val isWrapped = !FoxProcessExt.isRootLoader()
        private val callers = ArrayList<String>()
        @JvmField
        var o = false
        private var SHOWCASE_MODE_TRUE: String? = null
        private var secret: Long = 0
        private var timeFormatLocale = Resources.getSystem().configuration.locales[0]
        private var timeFormat = SimpleDateFormat(timeFormatString, timeFormatLocale)
        private var relPackageName = BuildConfig.APPLICATION_ID

        @SuppressLint("StaticFieldLeak")
        @JvmStatic
        var INSTANCE: MainApplication? = null
            private set
            get() {
                if (field == null) {
                    Timber.w("MainApplication.INSTANCE is null, using fallback!")
                    return null
                }
                return field
            }

        @JvmStatic
        var isFirstBoot = false
        private var mSharedPrefs: HashMap<Any, Any>? = null
        var updateCheckBg: String? = null

        init {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER).setTimeout(15)
                    .setInitializers(
                        InstallerInitializer::class.java
                    ).also { shellBuilder = it })
            val random = Random()
            do {
                secret = random.nextLong()
            } while (secret == 0L)
        }

        fun build(vararg command: String?): Shell {
            return shellBuilder!!.build(*command)
        }

        fun addSecret(intent: Intent) {
            val componentName = intent.component
            val packageName = componentName?.packageName ?: (intent.getPackage())!!
            require(
                !(!BuildConfig.APPLICATION_ID.equals(
                    packageName,
                    ignoreCase = true
                ) && relPackageName != packageName)
            ) {
                // Code safeguard, we should never reach here.
                "Can't add secret to outbound Intent"
            }
            intent.putExtra("secret", secret)
        }

        @Suppress("NAME_SHADOWING")
        @JvmStatic
        fun getSharedPreferences(name: String): SharedPreferences? {
            // encryptedSharedPreferences is used
            var name = name
            val mContext: Context? = INSTANCE
            name += "x"
            if (mSharedPrefs == null) {
                Timber.d("Creating shared prefs map")
                mSharedPrefs = HashMap()
            }
            /*
          this part is only here because with added encryption, parts of code that were previously calling this over and over again or on each invocation of a method are causing performance issues.
         */if (BuildConfig.DEBUG) {
                // get file, function, and line number
                val stackTrace = Thread.currentThread().stackTrace
                // get the caller of this method
                val caller = stackTrace[3]
                Timber.d(
                    "Shared prefs file: %s, caller: %s:%d in %s",
                    name,
                    caller.fileName,
                    caller.lineNumber,
                    caller.methodName
                )
                // add the caller to an array. if the last 3 callers are the same, then we are in a loop, log at error level
                callers.add(name + ":" + caller.lineNumber + ":" + caller.methodName)
                // get the last 3 callers
                val last3: List<String> =
                    callers.subList(max(callers.size - 3, 0), callers.size)
                // if the last 3 callers are the same, then we are in a loop, log at error level
                if (((last3.size == 3) && last3[0] == last3[1]) && last3[1] == last3[2]) {
                    Timber.e(
                        "Shared prefs loop detected. File: %s, caller: %s:%d",
                        name,
                        caller.methodName,
                        caller.lineNumber
                    )
                }
            }
            if (mSharedPrefs!!.containsKey(name)) {
                Timber.d("Returning cached shared prefs")
                return mSharedPrefs!![name] as SharedPreferences?
            }
            return try {
                Timber.d("Creating encrypted shared prefs")
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
                Timber.e(e, "Failed to create encrypted shared preferences")
                mContext!!.getSharedPreferences(name, MODE_PRIVATE)
            }
        }

        fun checkSecret(intent: Intent?): Boolean {
            return intent != null && intent.getLongExtra("secret", secret.inv()) == secret
        }

        // convert from String to boolean
        val isShowcaseMode: Boolean
            get() {
                if (SHOWCASE_MODE_TRUE != null) {
                    // convert from String to boolean
                    return java.lang.Boolean.parseBoolean(SHOWCASE_MODE_TRUE)
                }
                val showcaseMode = getSharedPreferences("mmm")!!
                    .getBoolean("pref_showcase_mode", false)
                SHOWCASE_MODE_TRUE = showcaseMode.toString()
                return showcaseMode
            }

        fun shouldPreventReboot(): Boolean {
            return getSharedPreferences("mmm")!!
                .getBoolean("pref_prevent_reboot", true)
        }

        val isShowIncompatibleModules: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_show_incompatible", false)
        val isForceDarkTerminal: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_force_dark_terminal", false)
        val isTextWrapEnabled: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_wrap_text", false)
        val isDohEnabled: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_dns_over_https", true)
        val isMonetEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && getSharedPreferences("mmm")!!
                .getBoolean("pref_enable_monet", true)
        val isBlurEnabled: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_enable_blur", false)
        @JvmStatic
        val isDeveloper: Boolean
            get() {
                return if (BuildConfig.DEBUG) true else getSharedPreferences("mmm")!!
                    .getBoolean("developer", false)
            }
        val isDisableLowQualityModuleFilter: Boolean
            get() = getSharedPreferences("mmm")!!
                .getBoolean("pref_disable_low_quality_module_filter", false) && isDeveloper
        val isUsingMagiskCommand: Boolean
            get() = (peekMagiskVersion() >= Constants.MAGISK_VER_CODE_INSTALL_COMMAND) && getSharedPreferences(
                "mmm"
            )!!
                .getBoolean("pref_use_magisk_install_command", false) && isDeveloper
        @JvmStatic
        val isBackgroundUpdateCheckEnabled: Boolean
            get() {
                if (updateCheckBg != null) {
                    return java.lang.Boolean.parseBoolean(updateCheckBg)
                }
                val wrapped = isWrapped
                val updateCheckBgTemp = !wrapped && getSharedPreferences("mmm")!!
                    .getBoolean("pref_background_update_check", true)
                updateCheckBg = updateCheckBgTemp.toString()
                return java.lang.Boolean.parseBoolean(updateCheckBg)
            }
        val isAndroidacyTestMode: Boolean
            get() = isDeveloper && getSharedPreferences("mmm")!!
                .getBoolean("pref_androidacy_test_mode", false)

        fun setHasGottenRootAccess(bool: Boolean) {
            getSharedPreferences("mmm")!!
                .edit().putBoolean("has_root_access", bool).apply()
        }

        val isCrashReportingEnabled: Boolean
            get() = SentryMain.IS_SENTRY_INSTALLED && getSharedPreferences("mmm")!!
                .getBoolean("pref_crash_reporting", BuildConfig.DEFAULT_ENABLE_CRASH_REPORTING)
        val bootSharedPreferences: SharedPreferences?
            get() = getSharedPreferences("mmm_boot")

        @JvmStatic
        fun formatTime(timeStamp: Long): String {
            // new Date(x) also get the local timestamp for format
            return timeFormat.format(Date(timeStamp))
        }

        @JvmStatic
        val isNotificationPermissionGranted: Boolean
            get() = NotificationManagerCompat.from((INSTANCE)!!).areNotificationsEnabled()

        @JvmStatic
        fun isMatomoAllowed(): Boolean {
            return getSharedPreferences("mmm")!!
                .getBoolean("pref_analytics_enabled", BuildConfig.DEFAULT_ENABLE_ANALYTICS)
        }
    }
}