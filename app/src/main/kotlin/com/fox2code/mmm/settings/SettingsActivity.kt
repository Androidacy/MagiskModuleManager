/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
@file:Suppress("DEPRECATION")

package com.fox2code.mmm.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentTransaction
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.CrashHandler
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityResume
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrl
import com.fox2code.mmm.utils.ProcessHelper.Companion.restartApplicationProcess
import com.fox2code.rosettax.LanguageActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.mikepenz.aboutlibraries.LibsBuilder
import timber.log.Timber

@Suppress("SENSELESS_COMPARISON")
class SettingsActivity : AppCompatActivity(), LanguageActivity,
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    private lateinit var bottomNavigationView: BottomNavigationView
    lateinit var sharedPreferences: SharedPreferences
    private lateinit var activeTabFromIntent: String

    @SuppressLint("RestrictedApi")
    private val onItemSelectedListener =
        NavigationBarView.OnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.installed_menu_item -> {
                    // go back to main modules by opening main activity with installed action
                    val intent = Intent(this, MainActivity::class.java)
                    intent.action = "android.intent.action.SHOW_INSTALLED"
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    return@OnItemSelectedListener true
                }

                R.id.online_menu_item -> {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.action = "android.intent.action.SHOW_ONLINE"
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(intent)
                    return@OnItemSelectedListener true
                }

                else -> {
                    return@OnItemSelectedListener false
                }
            }
        }


    @SuppressLint("RestrictedApi", "CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        devModeStep = 0
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // check for pref_crashed and if so start crash handler
        val sharedPreferences = MainApplication.getPreferences("mmm")
        if (sharedPreferences?.getBoolean("pref_crashed", false) == true) {
            val intent = Intent(this, CrashHandler::class.java)
            startActivity(intent)
            finish()
            return
        }
        // get the active tab from the intent
        activeTabFromIntent = intent.getStringExtra("activeTab") ?: "installed"
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback { preferenceFragmentCompat: PreferenceFragmentCompat, preference: Preference ->
            val fragment = supportFragmentManager.fragmentFactory.instantiate(
                classLoader, preference.fragment.toString()
            )
            fragment.arguments = preference.extras
            @Suppress("DEPRECATION") fragment.setTargetFragment(preferenceFragmentCompat, 0)
            supportFragmentManager.beginTransaction().replace(R.id.settings, fragment)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN).addToBackStack(null)
                .commit()
            true
        }

        setContentView(R.layout.settings_activity)
        val view = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setTitle(R.string.app_name_v2)
        MainApplication.getInstance().check(this)
        //hideActionBar();
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener(onItemSelectedListener)
        if (savedInstanceState == null) {
            val settingsFragment = SettingsFragment()
            supportFragmentManager.beginTransaction().replace(R.id.settings, settingsFragment)
                .commit()
        }

    }

    @SuppressLint("InlinedApi")
    override fun refreshRosettaX() {
        restartApplicationProcess(this)
    }

    override fun onPause() {
        onMainActivityResume(this)
        super.onPause()
    }

    annotation class PerformanceClass
    class SettingsFragment : PreferenceFragmentCompat() {
        @SuppressLint("UnspecifiedImmutableFlag")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val name = "mmmx"
            val context: Context? = MainApplication.getInstance()
            val masterKey: MasterKey
            val preferenceManager = preferenceManager
            val dataStore: SharedPreferenceDataStore
            try {
                masterKey =
                    MasterKey.Builder(context!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                dataStore = SharedPreferenceDataStore(
                    EncryptedSharedPreferences.create(
                        context,
                        name,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                )
                preferenceManager!!.preferenceDataStore = dataStore
                preferenceManager.sharedPreferencesName = "mmm"
            } catch (e: Exception) {
                Timber.e(e, "Failed to create encrypted shared preferences")
                throw RuntimeException(getString(R.string.error_encrypted_shared_preferences))
            }
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            // track all non empty values
            dataStore.sharedPreferences
            // disabled until EncryptedSharedPreferences fixes getAll()
            // add bottom navigation bar to the settings
            val bottomNavigationView =
                requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
            if (bottomNavigationView != null) {
                bottomNavigationView.visibility = View.VISIBLE
                bottomNavigationView.menu.findItem(R.id.settings_menu_item).isChecked = true
            }

            requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager

            // Determine if this is an official build based on the signature
            val flavor = BuildConfig.FLAVOR
            val type = BuildConfig.BUILD_TYPE
            // Set the summary of pref_pkg_info to something like default-debug v1.0 (123) (Official)
            val pkgInfo = getString(
                R.string.pref_pkg_info_summary,
                "$flavor-$type",
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                if (MainApplication.o) getString(
                    R.string.official
                ) else getString(R.string.unofficial)
            )
            findPreference<Preference>("pref_pkg_info")!!.summary = pkgInfo
            // special easter egg :)
            var versionClicks = 0
            findPreference<Preference>("pref_pkg_info")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    versionClicks++
                    if (MainApplication.forceDebugLogging) Timber.d(
                        "Version clicks: %d", versionClicks
                    )
                    if (versionClicks == 7) {
                        versionClicks = 0
                        openUrl(p.context, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                    }
                    // enable dev mode if it's disabled otherwise disable it
                    dataStore.sharedPreferences.edit {
                        if (dataStore.sharedPreferences.getBoolean("developer", false)) {
                            putBoolean("developer", false)
                            Toast.makeText(
                                p.context, R.string.dev_mode_disabled, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                p.context, R.string.dev_mode_enabled, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    // toast yer a wizard harry
                    if (versionClicks == 3) {
                        Toast.makeText(
                            p.context, R.string.keep_tapping_to_enter_hogwarts, Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }

            // libslistener to fix that the libs view doesn't actually go away when user goes back
            val libsBuilder = LibsBuilder().withShowLoadingProgress(true).withLicenseShown(true)
                .withAboutMinimalDesign(false).withLicenseDialog(true).withVersionShown(true)
            findPreference<Preference>("pref_show_licenses")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    // not correctly themed but less buggy than fragment
                    libsBuilder.start(requireContext())
                    return@OnPreferenceClickListener true
                }
            findPreference<Preference>("pref_show_apps")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        "https://play.google.com/store/apps/dev?id=6763514284252789381".toUri()
                    )
                    startActivity(browserIntent)
                    return@OnPreferenceClickListener true
                }
        }

    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        // Shamelessly adapted from https://github.com/DrKLO/Telegram/blob/2c71f6c92b45386f0c2b25f1442596462404bb39/TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java#L1254
        const val PERFORMANCE_CLASS_LOW = 0
        const val PERFORMANCE_CLASS_AVERAGE = 1
        const val PERFORMANCE_CLASS_HIGH = 2
        private var devModeStep = 0

        @get:PerformanceClass
        val devicePerformanceClass: Int
            get() {
                // special algorithm to determine performance class. low is < 4 cores and/ore < 4GB ram, mid is 4-6 cores and 4-6GB ram, high is > 6 cores and > 6GB ram. android sdk version is used as well
                // device is awarded 1 point for each core and 1 point for each GB of ram.
                var points = 0
                val cores = Runtime.getRuntime().availableProcessors()
                val activityManager = MainApplication.getInstance().getSystemService(
                    ACTIVITY_SERVICE
                ) as ActivityManager
                if (activityManager != null) {
                    val memoryInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memoryInfo)
                    val totalMemory = memoryInfo.totalMem
                    points += cores
                    points += (totalMemory / 1024 / 1024 / 1024).toInt()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    points += 1
                }
                if (MainApplication.forceDebugLogging) Timber.d(
                    "Device performance class: %d", points
                )
                return if (points <= 7) {
                    PERFORMANCE_CLASS_LOW
                } else if (points <= 12) {
                    PERFORMANCE_CLASS_AVERAGE
                } else {
                    PERFORMANCE_CLASS_HIGH
                }
            }

    }

    @SuppressLint("CommitTransaction")
    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat, pref: Preference
    ): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(
            classLoader, pref.fragment.toString()
        )
        fragment.arguments = pref.extras
        @Suppress("DEPRECATION") fragment.setTargetFragment(caller, 0)
        supportFragmentManager.beginTransaction().replace(R.id.settings, fragment)
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN).addToBackStack(null).commit()
        return true
    }
}
