/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import androidx.room.Room.databaseBuilder
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.foxcompat.view.FoxViewCompat
import com.fox2code.mmm.AppUpdateManager.Companion.appUpdateManager
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.ExpiredActivity
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.MainApplication.Companion.INSTANCE
import com.fox2code.mmm.MainApplication.Companion.getSharedPreferences
import com.fox2code.mmm.MainApplication.Companion.isBackgroundUpdateCheckEnabled
import com.fox2code.mmm.MainApplication.Companion.isCrashReportingEnabled
import com.fox2code.mmm.MainApplication.Companion.isDeveloper
import com.fox2code.mmm.MainApplication.Companion.isFirstBoot
import com.fox2code.mmm.MainApplication.Companion.isNotificationPermissionGranted
import com.fox2code.mmm.R
import com.fox2code.mmm.UpdateActivity
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityResume
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.postNotification
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleManager
import com.fox2code.mmm.module.ActionButtonType.Companion.donateIconForUrl
import com.fox2code.mmm.module.ActionButtonType.Companion.supportIconForUrl
import com.fox2code.mmm.repo.CustomRepoData
import com.fox2code.mmm.repo.RepoData
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.repo.RepoManager.Companion.getINSTANCE
import com.fox2code.mmm.repo.RepoManager.Companion.internalIdOfUrl
import com.fox2code.mmm.settings.LongClickablePreference.OnPreferenceLongClickListener
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.IntentHelper.Companion.openUrl
import com.fox2code.mmm.utils.ProcessHelper.Companion.restartApplicationProcess
import com.fox2code.mmm.utils.io.net.Http.Companion.setDoh
import com.fox2code.mmm.utils.room.ReposListDatabase
import com.fox2code.mmm.utils.sentry.SentryMain
import com.fox2code.rosettax.LanguageActivity
import com.fox2code.rosettax.LanguageSwitcher
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import com.mikepenz.aboutlibraries.LibsBuilder
import com.topjohnwu.superuser.internal.UiThreadHandler
import org.apache.commons.io.FileUtils
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.sql.Timestamp
import java.util.Objects
import java.util.Random
import kotlin.system.exitProcess

@Suppress("SENSELESS_COMPARISON")
class SettingsActivity : FoxActivity(), LanguageActivity {
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

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        devModeStep = 0
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(INSTANCE!!.getTracker())
        setContentView(R.layout.settings_activity)
        setTitle(R.string.app_name_v2)
        val ts = Timestamp(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        val buildTime = Timestamp(BuildConfig.BUILD_TIME)
        if (BuildConfig.DEBUG) {
            if (ts.time > buildTime.time) {
                val pm = packageManager
                val intent = Intent(this, ExpiredActivity::class.java)
                @Suppress("DEPRECATION") val resolveInfo = pm.queryIntentActivities(intent, 0)
                if (resolveInfo.size > 0) {
                    startActivity(intent)
                    finish()
                    return
                } else {
                    throw IllegalAccessError("This build has expired")
                }
            }
        } else {
            val ts2 = Timestamp(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
            if (ts2.time > buildTime.time) {
                Toast.makeText(this, R.string.build_expired, Toast.LENGTH_LONG).show()
            }
        }
        //hideActionBar();
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
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
    class SettingsFragment : PreferenceFragmentCompat(), OnBackPressedCallback {
        @SuppressLint("UnspecifiedImmutableFlag")
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val name = "mmmx"
            val context: Context? = INSTANCE
            val masterKey: MasterKey
            val preferenceManager = preferenceManager
            val dataStore: SharedPreferenceDataStore
            val editor: SharedPreferences.Editor
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
                editor = dataStore.sharedPreferences.edit()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create encrypted shared preferences")
                throw RuntimeException(getString(R.string.error_encrypted_shared_preferences))
            }
            setPreferencesFromResource(R.xml.root_preferences, rootKey)
            RepoFragment.applyMaterial3(preferenceScreen)
            // track all non empty values
            val sharedPreferences = dataStore.sharedPreferences
            // disabled until EncryptedSharedPreferences fixes getAll()
            // add bottom navigation bar to the settings
            val bottomNavigationView =
                requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
            if (bottomNavigationView != null) {
                bottomNavigationView.visibility = View.VISIBLE
                bottomNavigationView.menu.findItem(R.id.settings_menu_item).isChecked = true
            }
            findPreference<Preference>("pref_manage_repos")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    devModeStep = 0
                    openFragment(RepoFragment(), R.string.manage_repos_pref)
                    true
                }
            val themePreference = findPreference<ListPreference>("pref_theme")
            // If transparent theme(s) are set, disable monet
            if (themePreference!!.value == "transparent_light") {
                Timber.d("disabling monet")
                findPreference<Preference>("pref_enable_monet")!!.isEnabled = false
                // Toggle monet off
                (findPreference<Preference>("pref_enable_monet") as TwoStatePreference?)!!.isChecked =
                    false
                editor.putBoolean("pref_enable_monet", false).apply()
                // Set summary
                findPreference<Preference>("pref_enable_monet")!!.setSummary(R.string.monet_disabled_summary)
                // Same for blur
                findPreference<Preference>("pref_enable_blur")!!.isEnabled = false
                (findPreference<Preference>("pref_enable_blur") as TwoStatePreference?)!!.isChecked =
                    false
                editor.putBoolean("pref_enable_blur", false).apply()
                findPreference<Preference>("pref_enable_blur")!!.setSummary(R.string.blur_disabled_summary)
            }
            themePreference.summaryProvider =
                SummaryProvider { _: Preference? -> themePreference.entry }
            themePreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                    // You need to reboot your device at least once to be able to access dev-mode
                    if (devModeStepFirstBootIgnore || !isFirstBoot) devModeStep = 1
                    Timber.d("refreshing activity. New value: %s", newValue)
                    editor.putString("pref_theme", newValue as String).apply()
                    // If theme contains "transparent" then disable monet
                    if (newValue.toString().contains("transparent")) {
                        Timber.d("disabling monet")
                        // Show a dialogue warning the user about issues with transparent themes and
                        // that blur/monet will be disabled
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.transparent_theme_dialogue_title)
                            .setMessage(
                                R.string.transparent_theme_dialogue_message
                            )
                            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                                // Toggle monet off
                                (findPreference<Preference>("pref_enable_monet") as TwoStatePreference?)!!.isChecked =
                                    false
                                editor.putBoolean("pref_enable_monet", false).apply()
                                // Set summary
                                findPreference<Preference>("pref_enable_monet")!!.setSummary(R.string.monet_disabled_summary)
                                // Same for blur
                                (findPreference<Preference>("pref_enable_blur") as TwoStatePreference?)!!.isChecked =
                                    false
                                editor.putBoolean("pref_enable_blur", false).apply()
                                findPreference<Preference>("pref_enable_blur")!!.setSummary(R.string.blur_disabled_summary)
                                // Refresh activity
                                devModeStep = 0
                                UiThreadHandler.handler.postDelayed({
                                    INSTANCE!!.updateTheme()
                                    getFoxActivity(this).setThemeRecreate(INSTANCE!!.getManagerThemeResId())
                                }, 1)
                                val intent = Intent(requireContext(), SettingsActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                startActivity(intent)
                            }
                            .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int ->
                                // Revert to system theme
                                (findPreference<Preference>("pref_theme") as ListPreference?)!!.value =
                                    "system"
                                // Refresh activity
                                devModeStep = 0
                            }.show()
                    } else {
                        findPreference<Preference>("pref_enable_monet")!!.isEnabled = true
                        findPreference<Preference>("pref_enable_monet")?.summary = ""
                        findPreference<Preference>("pref_enable_blur")!!.isEnabled = true
                        findPreference<Preference>("pref_enable_blur")?.summary = ""
                        devModeStep = 0
                    }
                    UiThreadHandler.handler.postDelayed({
                        INSTANCE!!.updateTheme()
                        getFoxActivity(this).setThemeRecreate(INSTANCE!!.getManagerThemeResId())
                    }, 1)
                    true
                }
            // Crash reporting
            val crashReportingPreference =
                findPreference<TwoStatePreference>("pref_crash_reporting")
            if (!SentryMain.IS_SENTRY_INSTALLED) crashReportingPreference!!.isVisible = false
            crashReportingPreference!!.isChecked = isCrashReportingEnabled
            val initialValue: Any = isCrashReportingEnabled
            crashReportingPreference.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener setOnPreferenceChangeListener@{ _: Preference?, newValue: Any ->
                    devModeStepFirstBootIgnore = true
                    devModeStep = 0
                    if (initialValue === newValue) return@setOnPreferenceChangeListener true
                    // Show a dialog to restart the app
                    val materialAlertDialogBuilder = MaterialAlertDialogBuilder(requireContext())
                    materialAlertDialogBuilder.setTitle(R.string.crash_reporting_restart_title)
                    materialAlertDialogBuilder.setMessage(R.string.crash_reporting_restart_message)
                    materialAlertDialogBuilder.setPositiveButton(R.string.restart) { _: DialogInterface?, _: Int ->
                        val mStartActivity = Intent(requireContext(), MainActivity::class.java)
                        mStartActivity.flags =
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        val mPendingIntentId = 123456
                        // If < 23, FLAG_IMMUTABLE is not available
                        val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                            requireContext(),
                            mPendingIntentId,
                            mStartActivity,
                            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val mgr = requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                        mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] = mPendingIntent
                        Timber.d("Restarting app to save crash reporting preference: %s", newValue)
                        exitProcess(0) // Exit app process
                    }
                    // Do not reverse the change if the user cancels the dialog
                    materialAlertDialogBuilder.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int -> }
                    materialAlertDialogBuilder.show()
                    true
                }
            val enableBlur = findPreference<Preference>("pref_enable_blur")
            // Disable blur on low performance devices
            if (devicePerformanceClass < PERFORMANCE_CLASS_AVERAGE) {
                // Show a warning
                enableBlur!!.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                        if (newValue == true) {
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.low_performance_device_dialogue_title)
                                .setMessage(
                                    R.string.low_performance_device_dialogue_message
                                )
                                .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                                    // Toggle blur on
                                    (findPreference<Preference>("pref_enable_blur") as TwoStatePreference?)!!.isChecked =
                                        true
                                    editor.putBoolean("pref_enable_blur", true).apply()
                                    // Set summary
                                    findPreference<Preference>("pref_enable_blur")!!.setSummary(R.string.blur_disabled_summary)
                                }
                                .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int ->
                                    // Revert to blur on
                                    (findPreference<Preference>("pref_enable_blur") as TwoStatePreference?)!!.isChecked =
                                        false
                                    editor.putBoolean("pref_enable_blur", false).apply()
                                    // Set summary
                                    findPreference<Preference>("pref_enable_blur")?.summary =
                                        getString(R.string.blur_performance_warning_summary)
                                }.show()
                        }
                        true
                    }
            }
            val disableMonet = findPreference<Preference>("pref_enable_monet")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                disableMonet!!.setSummary(R.string.require_android_12)
                disableMonet.isEnabled = false
            }
            disableMonet!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    UiThreadHandler.handler.postDelayed({
                        INSTANCE!!.updateTheme()
                        (requireActivity() as FoxActivity).setThemeRecreate(INSTANCE!!.getManagerThemeResId())
                    }, 1)
                    true
                }
            findPreference<Preference>("pref_dns_over_https")!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, v: Any? ->
                    setDoh(
                        (v as Boolean?)!!
                    )
                    true
                }

            // handle restart required for showcase mode
            findPreference<Preference>("pref_showcase_mode")!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, v: Any ->
                    if (v == true) {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.restart)
                            .setMessage(R.string.showcase_mode_dialogue_message).setPositiveButton(
                            R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            // Toggle showcase mode on
                            (findPreference<Preference>("pref_showcase_mode") as TwoStatePreference?)!!.isChecked =
                                true
                            editor.putBoolean("pref_showcase_mode", true).apply()
                            // restart app
                            val mStartActivity = Intent(requireContext(), MainActivity::class.java)
                            mStartActivity.flags =
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            val mPendingIntentId = 123456
                                val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                                    requireContext(),
                                    mPendingIntentId,
                                    mStartActivity,
                                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                            val mgr =
                                requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                            mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] = mPendingIntent
                            Timber.d("Restarting app to save showcase mode preference: %s", v)
                            exitProcess(0) // Exit app process
                        }
                            .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int ->
                                // Revert to showcase mode on
                                (findPreference<Preference>("pref_showcase_mode") as TwoStatePreference?)!!.isChecked =
                                    false
                                editor.putBoolean("pref_showcase_mode", false).apply()
                                // restart app
                                val mStartActivity =
                                    Intent(requireContext(), MainActivity::class.java)
                                mStartActivity.flags =
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                val mPendingIntentId = 123456
                                val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                                    requireContext(),
                                    mPendingIntentId,
                                    mStartActivity,
                                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                val mgr =
                                    requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                                mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                    mPendingIntent
                                Timber.d("Restarting app to save showcase mode preference: %s", v)
                                exitProcess(0) // Exit app process
                            }.show()
                    }
                    true
                }
            val languageSelector = findPreference<Preference>("pref_language_selector")
            languageSelector!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    val ls = LanguageSwitcher(
                        requireActivity()
                    )
                    ls.setSupportedStringLocales(MainApplication.supportedLocales)
                    ls.showChangeLanguageDialog(activity)
                    true
                }

            // Handle pref_language_selector_cta by taking user to https://translate.nift4.org/engage/foxmmm/
            val languageSelectorCta =
                findPreference<LongClickablePreference>("pref_language_selector_cta")
            languageSelectorCta!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    val browserIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://translate.nift4.org/engage/foxmmm/")
                    )
                    startActivity(browserIntent)
                    true
                }

            // Long click to copy url
            languageSelectorCta.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val clipboard =
                        requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip =
                        ClipData.newPlainText("URL", "https://translate.nift4.org/engage/foxmmm/")
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT)
                        .show()
                    true
                }
            val level = currentLanguageLevel()
            if (level != LANGUAGE_SUPPORT_LEVEL) {
                Timber.e("latest is %s", LANGUAGE_SUPPORT_LEVEL)
                languageSelector.setSummary(R.string.language_support_outdated)
            } else {
                val translatedBy = this.getString(R.string.language_translated_by)
                // I don't "translate" english
                if (!("Translated by Fox2Code (Put your name here)" == translatedBy || "Translated by Fox2Code" == translatedBy)) {
                    languageSelector.setSummary(R.string.language_translated_by)
                } else {
                    languageSelector.summary = null
                }
            }
            if (!isDeveloper) {
                findPreference<Preference>("pref_disable_low_quality_module_filter")!!.isVisible =
                    false
                // Find pref_clear_data and set it invisible
                findPreference<Preference>("pref_clear_data")!!.isVisible = false
            }
            // hande clear cache
            findPreference<Preference>("pref_clear_cache")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    // Clear cache
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.clear_cache_dialogue_title)
                        .setMessage(
                            R.string.clear_cache_dialogue_message
                        ).setPositiveButton(R.string.yes) { _: DialogInterface?, _: Int ->
                        // Clear app cache
                        try {
                            // use apache commons IO to delete the cache
                            FileUtils.deleteDirectory(requireContext().cacheDir)
                            // create a new cache dir
                            FileUtils.forceMkdir(requireContext().cacheDir)
                            // create cache dirs for cronet and webview
                            FileUtils.forceMkdir(File(requireContext().cacheDir, "cronet"))
                            FileUtils.forceMkdir(File(INSTANCE!!.dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/wasm"))
                            FileUtils.forceMkdir(File(INSTANCE!!.dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/js"))
                            Toast.makeText(
                                requireContext(),
                                R.string.cache_cleared,
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Timber.e(e)
                            Toast.makeText(
                                requireContext(),
                                R.string.cache_clear_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int -> }
                        .show()
                    true
                }
            if (!SentryMain.IS_SENTRY_INSTALLED || !BuildConfig.DEBUG || peekMagiskPath() == null) {
                // Hide the pref_crash option if not in debug mode - stop users from purposely crashing the app
                Timber.i(peekMagiskPath())
                findPreference<Preference?>("pref_test_crash")!!.isVisible = false
            } else {
                if (findPreference<Preference?>("pref_test_crash") != null && findPreference<Preference?>(
                        "pref_clear_data"
                    ) != null
                ) {
                    findPreference<Preference>("pref_test_crash")!!.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener { _: Preference? ->
                            throw RuntimeException("This is a test crash with a stupidly long description to show off the crash handler. Are we having fun yet?")
                        }
                    findPreference<Preference>("pref_clear_data")!!.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener { _: Preference? ->
                            // Clear app data
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.clear_data_dialogue_title)
                                .setMessage(
                                    R.string.clear_data_dialogue_message
                                )
                                .setPositiveButton(R.string.yes) { _: DialogInterface?, _: Int ->
                                    // Clear app data
                                    INSTANCE!!.resetApp()
                                }
                                .setNegativeButton(R.string.no) { _: DialogInterface?, _: Int -> }
                                .show()
                            true
                        }
                } else {
                    Timber.e(
                        "Something is null: %s, %s",
                        findPreference("pref_clear_data"),
                        findPreference("pref_test_crash")
                    )
                }
            }
            if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND || !isDeveloper) {
                findPreference<Preference>("pref_use_magisk_install_command")!!.isVisible = false
            }
            val debugNotification = findPreference<Preference>("pref_background_update_check_debug")
            val updateCheckExcludes =
                findPreference<Preference>("pref_background_update_check_excludes")
            val updateCheckVersionExcludes =
                findPreference<Preference>("pref_background_update_check_excludes_version")
            debugNotification!!.isEnabled = isBackgroundUpdateCheckEnabled
            debugNotification.isVisible =
                isDeveloper && !MainApplication.isWrapped && isBackgroundUpdateCheckEnabled
            debugNotification.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    // fake updatable modules hashmap
                    val updateableModules = HashMap<String, String>()
                    // count of modules to fake must match the count in the random number generator
                    val random = Random()
                    var count: Int
                    do {
                        count = random.nextInt(4) + 2
                    } while (count == 2)
                    for (i in 0 until count) {
                        var fakeVersion: Int
                        do {
                            fakeVersion = random.nextInt(10)
                        } while (fakeVersion == 0)
                        Timber.d("Fake version: %s, count: %s", fakeVersion, i)
                        updateableModules["FakeModule $i"] = "1.0.$fakeVersion"
                    }
                    postNotification(requireContext(), updateableModules, count, true)
                    true
                }
            val backgroundUpdateCheck = findPreference<Preference>("pref_background_update_check")
            backgroundUpdateCheck!!.isVisible = !MainApplication.isWrapped
            // Make uncheckable if POST_NOTIFICATIONS permission is not granted
            if (!isNotificationPermissionGranted) {
                // Instead of disabling the preference, we make it uncheckable and when the user
                // clicks on it, we show a dialog explaining why the permission is needed
                backgroundUpdateCheck.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener { _: Preference? ->
                        // set the box to unchecked
                        (backgroundUpdateCheck as SwitchPreferenceCompat?)!!.isChecked = false
                        // ensure that the preference is false
                        getSharedPreferences("mmm")!!
                            .edit().putBoolean("pref_background_update_check", false).apply()
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.permission_notification_title)
                            .setMessage(
                                R.string.permission_notification_message
                            )
                            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                                // Open the app settings
                                val intent = Intent()
                                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                val uri =
                                    Uri.fromParts("package", requireContext().packageName, null)
                                intent.data = uri
                                this.startActivity(intent)
                            }
                            .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
                            .show()
                        true
                    }
                backgroundUpdateCheck.setSummary(R.string.background_update_check_permission_required)
            }
            updateCheckExcludes!!.isVisible =
                isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
            backgroundUpdateCheck.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                    val enabled = java.lang.Boolean.parseBoolean(newValue.toString())
                    debugNotification.isEnabled = enabled
                    debugNotification.isVisible =
                        isDeveloper && !MainApplication.isWrapped && enabled
                    updateCheckExcludes.isEnabled = enabled
                    updateCheckExcludes.isVisible = enabled && !MainApplication.isWrapped
                    if (!enabled) {
                        onMainActivityResume(requireContext())
                    }
                    true
                }
            // updateCheckExcludes saves to pref_background_update_check_excludes as a stringset. On clicking, it should open a dialog with a list of all installed modules
            updateCheckExcludes.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    val localModuleInfos: Collection<LocalModuleInfo?> =
                        ModuleManager.instance!!.modules.values
                    // make sure we have modules
                    val checkedItems: BooleanArray
                    if (!localModuleInfos.isEmpty()) {
                        val moduleNames = arrayOfNulls<String>(localModuleInfos.size)
                        checkedItems = BooleanArray(localModuleInfos.size)
                        // get the stringset pref_background_update_check_excludes
                        val stringSetTemp = sharedPreferences.getStringSet(
                            "pref_background_update_check_excludes",
                            HashSet()
                        )
                        // copy to a new set so we can modify it
                        val stringSet: MutableSet<String> = HashSet(stringSetTemp!!)
                        for ((i, localModuleInfo) in localModuleInfos.withIndex()) {
                            moduleNames[i] = localModuleInfo!!.name
                            // Stringset uses id, we show name
                            checkedItems[i] = stringSet.contains(localModuleInfo.id)
                            Timber.d("name: %s, checked: %s", moduleNames[i], checkedItems[i])
                        }
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                            .setMultiChoiceItems(
                                moduleNames,
                                checkedItems
                            ) { _: DialogInterface?, which: Int, isChecked: Boolean ->
                                // get id from name
                                val id: String = if (localModuleInfos.stream()
                                        .anyMatch { localModuleInfo: LocalModuleInfo? -> localModuleInfo!!.name == moduleNames[which] }
                                ) {
                                    localModuleInfos.stream()
                                        .filter { localModuleInfo: LocalModuleInfo? ->
                                            localModuleInfo!!.name.equals(
                                                moduleNames[which]
                                            )
                                        }.findFirst().orElse(null)!!.id
                                } else {
                                    ""
                                }
                                if (id.isNotEmpty()) {
                                    if (isChecked) {
                                        stringSet.add(id)
                                    } else {
                                        stringSet.remove(id)
                                    }
                                }
                                sharedPreferences.edit().putStringSet(
                                    "pref_background_update_check_excludes",
                                    stringSet
                                ).apply()
                            }
                            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }
                            .show()
                    } else {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                            .setMessage(
                                R.string.background_update_check_excludes_no_modules
                            )
                            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }
                            .show()
                    }
                    true
                }
            // now handle pref_background_update_check_excludes_version
            updateCheckVersionExcludes!!.isVisible =
                isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
            updateCheckVersionExcludes.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    // get the stringset pref_background_update_check_excludes_version
                    val stringSet = sharedPreferences.getStringSet(
                        "pref_background_update_check_excludes_version",
                        HashSet()
                    )
                    Timber.d("stringSet: %s", stringSet)
                    // for every module, add it's name and a text field to the dialog. the text field should accept a comma separated list of versions
                    val localModuleInfos: Collection<LocalModuleInfo?> =
                        ModuleManager.instance!!.modules.values
                    // make sure we have modules
                    if (localModuleInfos.isEmpty()) {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                            .setMessage(
                                R.string.background_update_check_excludes_no_modules
                            )
                            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }
                            .show()
                    } else {
                        val layout = LinearLayout(requireContext())
                        layout.orientation = LinearLayout.VERTICAL
                        val params = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        params.setMargins(48, 0, 48, 0)
                        // add a summary
                        val textView = MaterialTextView(requireContext())
                        textView.layoutParams = params
                        textView.setText(R.string.background_update_check_excludes_version_summary)
                        for (localModuleInfo in localModuleInfos) {
                            // two views: materialtextview for name, edittext for version
                            val materialTextView = MaterialTextView(requireContext())
                            materialTextView.layoutParams = params
                            materialTextView.setPadding(12, 8, 12, 8)
                            materialTextView.setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                            materialTextView.text = localModuleInfo!!.name
                            layout.addView(materialTextView)
                            val editText = EditText(requireContext())
                            editText.inputType =
                                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            editText.layoutParams = params
                            editText.setHint(R.string.background_update_check_excludes_version_hint)
                            // stringset uses id:version, we show version for name
                            // so we need to get id from name, then get version from stringset
                            val id = localModuleInfos.stream()
                                .filter { localModuleInfo1: LocalModuleInfo? ->
                                    localModuleInfo1!!.name.equals(
                                        localModuleInfo.name
                                    )
                                }.findFirst().orElse(null)!!.id
                            val version =
                                stringSet!!.stream().filter { s: String -> s.startsWith(id) }
                                    .findFirst().orElse("")
                            if (version.isNotEmpty()) {
                                editText.setText(
                                    version.split(":".toRegex()).dropLastWhile { it.isEmpty() }
                                        .toTypedArray()[1]
                                )
                            }
                            layout.addView(editText)
                        }
                        val scrollView = ScrollView(requireContext())
                        scrollView.addView(layout)
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes_version)
                            .setView(scrollView).setPositiveButton(
                            R.string.ok
                        ) { _: DialogInterface?, _: Int ->
                            Timber.d("ok clicked")
                            // for every module, get the text field and save it to the stringset
                            val stringSetTemp: MutableSet<String> = HashSet()
                            var prevMod = ""
                            for (i in 0 until layout.childCount) {
                                if (layout.getChildAt(i) is MaterialTextView) {
                                    val mv = layout.getChildAt(i) as MaterialTextView
                                    prevMod = mv.text.toString()
                                    continue
                                }
                                val editText = layout.getChildAt(i) as EditText
                                var text = editText.text.toString()
                                if (text.isNotEmpty()) {
                                    // text can only contain numbers and the characters ^ and $
                                    // so we remove all non-numbers and non ^ and $
                                    text = text.replace("[^0-9^$]".toRegex(), "")
                                    // we have to use module id even though we show name
                                    val finalprevMod = prevMod
                                    stringSetTemp.add(
                                        localModuleInfos.stream()
                                            .filter { localModuleInfo: LocalModuleInfo? ->
                                                localModuleInfo!!.name.equals(finalprevMod)
                                            }
                                            .findFirst().orElse(null)!!.id + ":" + text)
                                    Timber.d("text is %s for %s", text, editText.hint.toString())
                                } else {
                                    Timber.d("text is empty for %s", editText.hint.toString())
                                }
                            }
                            sharedPreferences.edit().putStringSet(
                                "pref_background_update_check_excludes_version",
                                stringSetTemp
                            ).apply()
                        }
                            .setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
                            .show()
                    }
                    true
                }
            val libsBuilder = LibsBuilder().withShowLoadingProgress(false).withLicenseShown(true)
                .withAboutMinimalDesign(false)
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            var linkClickable = findPreference<LongClickablePreference>("pref_update")
            linkClickable!!.isVisible =
                BuildConfig.ENABLE_AUTO_UPDATER && (BuildConfig.DEBUG || appUpdateManager.peekHasUpdate())
            linkClickable.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    devModeStep = 0
                    // open UpdateActivity with CHECK action
                    val intent = Intent(requireContext(), UpdateActivity::class.java)
                    intent.action = UpdateActivity.ACTIONS.CHECK.name
                    startActivity(intent)
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            "https://github.com/Androidacy/MagiskModuleManager/releases/latest"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            // for pref_background_update_check_debug_download, do the same as pref_update except with DOWNLOAD action
            val debugDownload =
                findPreference<Preference>("pref_background_update_check_debug_download")
            debugDownload!!.isVisible =
                isDeveloper && isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
            debugDownload.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    devModeStep = 0
                    val intent = Intent(requireContext(), UpdateActivity::class.java)
                    intent.action = UpdateActivity.ACTIONS.DOWNLOAD.name
                    startActivity(intent)
                    true
                }
            if (BuildConfig.DEBUG || BuildConfig.ENABLE_AUTO_UPDATER) {
                linkClickable = findPreference("pref_report_bug")
                linkClickable!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener { p: Preference ->
                        devModeStep = 0
                        devModeStepFirstBootIgnore = true
                        openUrl(
                            p.context,
                            "https://github.com/Androidacy/MagiskModuleManager/issues"
                        )
                        true
                    }
                linkClickable.onPreferenceLongClickListener =
                    OnPreferenceLongClickListener { _: Preference? ->
                        val toastText = requireContext().getString(R.string.link_copied)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                toastText,
                                "https://github.com/Androidacy/MagiskModuleManager/issues"
                            )
                        )
                        Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                        true
                    }
            } else {
                findPreference<Preference>("pref_report_bug")!!.isVisible = false
            }
            linkClickable = findPreference("pref_source_code")
            // Set summary to the last commit this build was built from @ User/Repo
            // Build userRepo by removing all parts of REMOTE_URL that are not the user/repo
            var userRepo = BuildConfig.REMOTE_URL
            // remove .git
            userRepo = userRepo.replace("\\.git$".toRegex(), "")
            Timber.d("userRepo: %s", userRepo)

            // finalUserRepo is the user/repo part of REMOTE_URL
            // get everything after .com/ or .org/ or .io/ or .me/ or .net/ or .xyz/ or .tk/ or .co/ minus .git
            val finalUserRepo = userRepo.replace(
                "^(https?://)?(www\\.)?(github\\.com|gitlab\\.com|bitbucket\\.org|git\\.io|git\\.me|git\\.net|git\\.xyz|git\\.tk|git\\.co)/".toRegex(),
                ""
            )
            linkClickable!!.summary = String.format(
                getString(R.string.source_code_summary),
                BuildConfig.COMMIT_HASH,
                finalUserRepo
            )
            Timber.d("finalUserRepo: %s", finalUserRepo)
            val finalUserRepo1 = userRepo
            linkClickable.onPreferenceClickListener =
                Preference.OnPreferenceClickListener setOnPreferenceClickListener@{ p: Preference ->
                    if (devModeStep == 2) {
                        devModeStep = 0
                        if (isDeveloper && !BuildConfig.DEBUG) {
                            getSharedPreferences("mmm")!!
                                .edit().putBoolean("developer", false).apply()
                            Toast.makeText(
                                getContext(),  // Tell the user something changed
                                R.string.dev_mode_disabled, Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            getSharedPreferences("mmm")!!
                                .edit().putBoolean("developer", true).apply()
                            Toast.makeText(
                                getContext(),  // Tell the user something changed
                                R.string.dev_mode_enabled, Toast.LENGTH_SHORT
                            ).show()
                        }
                        ExternalHelper.INSTANCE.refreshHelper(requireContext())
                        return@setOnPreferenceClickListener true
                    }
                    // build url from BuildConfig.REMOTE_URL and BuildConfig.COMMIT_HASH. May have to remove the .git at the end
                    openUrl(
                        p.context,
                        finalUserRepo1.replace(".git", "") + "/tree/" + BuildConfig.COMMIT_HASH
                    )
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            BuildConfig.REMOTE_URL + "/tree/" + BuildConfig.COMMIT_HASH
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            // Next, the pref_androidacy_thanks should lead to the androidacy website
            linkClickable = findPreference("pref_androidacy_thanks")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    openUrl(
                        p.context,
                        "https://www.androidacy.com?utm_source=FoxMagiskModuleManager&utm_medium=app&utm_campaign=FoxMagiskModuleManager"
                    )
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            "https://www.androidacy.com?utm_source=FoxMagiskModuleManager&utm_medium=app&utm_campaign=FoxMagiskModuleManager"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            // pref_fox2code_thanks should lead to https://github.com/Fox2Code
            linkClickable = findPreference("pref_fox2code_thanks")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    openUrl(p.context, "https://github.com/Fox2Code")
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            "https://github.com/Fox2Code"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            // handle pref_save_logs which saves logs to our external storage and shares them
            val saveLogs = findPreference<Preference>("pref_save_logs")
            saveLogs!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener setOnPreferenceClickListener@{ _: Preference? ->
                    // Save logs to external storage
                    val logsFile = File(requireContext().getExternalFilesDir(null), "logs.txt")
                    var fileOutputStream: FileOutputStream? = null
                    try {
                        logsFile.createNewFile()
                        fileOutputStream = FileOutputStream(logsFile)
                        // first, some device and app info: namely device oem and model, android version and build, app version and build
                        fileOutputStream.write(
                            String.format(
                                "Device: %s %s\nAndroid Version: %s\nROM: %s\nApp Version: %s (%s)\n\n",
                                Build.MANUFACTURER,
                                Build.MODEL,
                                Build.VERSION.RELEASE,
                                Build.FINGERPRINT,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            ).toByteArray()
                        )
                        // next, the logs
                        // get our logs from logcat
                        val process = Runtime.getRuntime().exec("logcat -d")
                        val bufferedReader = BufferedReader(
                            InputStreamReader(process.inputStream)
                        )
                        var line: String?
                        val iterator: Iterator<String> = bufferedReader.lines().iterator()
                        while (iterator.hasNext()) {
                            line = iterator.next()
                            fileOutputStream.write(line.toByteArray())
                            fileOutputStream.write("\n".toByteArray())
                        }
                        fileOutputStream.flush()
                        Toast.makeText(
                            requireContext(),
                            R.string.logs_saved,
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: IOException) {
                        e.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            R.string.error_saving_logs,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnPreferenceClickListener true
                    } finally {
                        if (fileOutputStream != null) {
                            try {
                                fileOutputStream.close()
                            } catch (ignored: IOException) {
                            }
                        }
                    }
                    // Share logs
                    val shareIntent = Intent()
                    // create a new intent and grantUriPermission to the file provider
                    shareIntent.action = Intent.ACTION_SEND
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    shareIntent.putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(
                            requireContext(),
                            BuildConfig.APPLICATION_ID + ".file-provider",
                            logsFile
                        )
                    )
                    shareIntent.type = "text/plain"
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
                    true
                }
            // pref_contributors should lead to the contributors page
            linkClickable = findPreference("pref_contributors")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    // Remove the .git if it exists and add /graphs/contributors
                    var url = BuildConfig.REMOTE_URL
                    if (url.endsWith(".git")) {
                        url = url.substring(0, url.length - 4)
                    }
                    url += "/graphs/contributors"
                    openUrl(p.context, url)
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    // Remove the .git if it exists and add /graphs/contributors
                    var url = BuildConfig.REMOTE_URL
                    if (url.endsWith(".git")) {
                        url = url.substring(0, url.length - 4)
                    }
                    url += "/graphs/contributors"
                    clipboard.setPrimaryClip(ClipData.newPlainText(toastText, url))
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            linkClickable = findPreference("pref_support")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    devModeStep = 0
                    openUrl(p.context, "https://t.me/Fox2Code_Chat")
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            "https://t.me/Fox2Code_Chat"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            // pref_announcements to https://t.me/androidacy
            linkClickable = findPreference("pref_announcements")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    devModeStep = 0
                    openUrl(p.context, "https://t.me/androidacy")
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText,
                            "https://t.me/androidacy"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
            findPreference<Preference>("pref_show_licenses")!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    devModeStep = if (devModeStep == 1) 2 else 0
                    onMainActivityResume(requireContext())
                    openFragment(libsBuilder.supportFragment(), R.string.licenses)
                    true
                }
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
                    Timber.d("Version clicks: %d", versionClicks)
                    // if it's been 3 clicks, toast "yer a wizard, harry" or "keep tapping to enter hogwarts"
                    if (versionClicks == 3) {
                        // random choice of 1 or 2
                        val rand = Random()
                        val n = rand.nextInt(2) + 1
                        Toast.makeText(
                            p.context,
                            if (n == 1) R.string.yer_a_wizard_harry else R.string.keep_tapping_to_enter_hogwarts,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (versionClicks == 7) {
                        versionClicks = 0
                        openUrl(p.context, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                    }
                    true
                }
            val prefDonateFox = findPreference<LongClickablePreference>("pref_donate_fox")
            if (BuildConfig.FLAVOR != "play") {
                prefDonateFox!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener { _: Preference? ->
                        // open fox
                        openUrl(getFoxActivity(this), "https://paypal.me/fox2code")
                        true
                    }
                // handle long click on pref_donate_fox
                prefDonateFox.onPreferenceLongClickListener =
                    OnPreferenceLongClickListener { _: Preference? ->
                        // copy to clipboard
                        val toastText = requireContext().getString(R.string.link_copied)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                toastText,
                                "https://paypal.me/fox2code"
                            )
                        )
                        Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                        true
                    }
            } else {
                prefDonateFox!!.isVisible = false
            }
            // now handle pref_donate_androidacy
            val prefDonateAndroidacy =
                findPreference<LongClickablePreference>("pref_donate_androidacy")
            if (BuildConfig.FLAVOR != "play") {
                if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == "Guest" || AndroidacyRepoData.instance.memberLevel == null) {
                    prefDonateAndroidacy!!.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener { _: Preference? ->
                            // copy FOX2CODE promo code to clipboard and toast user that they can use it for half off any subscription
                            val toastText = requireContext().getString(R.string.promo_code_copied)
                            clipboard.setPrimaryClip(ClipData.newPlainText(toastText, "FOX2CODE"))
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            // open androidacy
                            openUrl(
                                getFoxActivity(this),
                                "https://www.androidacy.com/membership-join/?utm_source=foxmmm&utm_medium=app&utm_campaign=donate"
                            )
                            true
                        }
                    // handle long click on pref_donate_androidacy
                    prefDonateAndroidacy.onPreferenceLongClickListener =
                        OnPreferenceLongClickListener { _: Preference? ->
                            // copy to clipboard
                            val toastText = requireContext().getString(R.string.link_copied)
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    toastText,
                                    "https://www.androidacy.com/membership-join/?utm_source=foxmmm&utm_medium=app&utm_campaign=donate"
                                )
                            )
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            true
                        }
                } else {
                    // set text to "Thank you for your support!"
                    prefDonateAndroidacy!!.setSummary(R.string.androidacy_thanks_up)
                    prefDonateAndroidacy.setTitle(R.string.androidacy_thanks_up_title)
                }
            } else {
                prefDonateAndroidacy!!.isVisible = false
            }
        }

        private fun openFragment(fragment: Fragment, @StringRes title: Int) {
            val compatActivity = getFoxActivity(this)
            compatActivity.onBackPressedCallback = this
            compatActivity.setTitle(title)
            compatActivity.supportFragmentManager.beginTransaction()
                .replace(R.id.settings, fragment).setTransition(
                FragmentTransaction.TRANSIT_FRAGMENT_FADE
            ).commit()
        }

        override fun onBackPressed(compatActivity: FoxActivity): Boolean {
            compatActivity.setTitle(R.string.app_name_v2)
            compatActivity.supportFragmentManager.beginTransaction().replace(R.id.settings, this)
                .setTransition(
                    FragmentTransaction.TRANSIT_FRAGMENT_FADE
                ).commit()
            return true
        }

        private fun currentLanguageLevel(): Int {
            val declaredLanguageLevel = this.resources.getInteger(R.integer.language_support_level)
            if (declaredLanguageLevel != LANGUAGE_SUPPORT_LEVEL) return declaredLanguageLevel
            return if (this.resources.configuration.locales[0].language != "en" && this.resources.getString(
                    R.string.notification_update_pref
                ) == "Background modules update check" && this.resources.getString(R.string.notification_update_desc) == "May increase battery usage"
            ) {
                0
            } else LANGUAGE_SUPPORT_LEVEL
        }
    }

    class RepoFragment : PreferenceFragmentCompat() {
        @SuppressLint("RestrictedApi", "UnspecifiedImmutableFlag")
        fun onCreatePreferencesAndroidacy() {
            // Bind the pref_show_captcha_webview to captchaWebview('https://production-api.androidacy.com/')
            // Also require dev mode
            // CaptchaWebview.setVisible(false);
            val androidacyTestMode =
                findPreference<Preference>("pref_androidacy_test_mode")!!
            if (!isDeveloper) {
                androidacyTestMode.isVisible = false
            } else {
                // Show a warning if user tries to enable test mode
                androidacyTestMode.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                        if (java.lang.Boolean.parseBoolean(newValue.toString())) {
                            // Use MaterialAlertDialogBuilder
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.warning)
                                .setCancelable(false).setMessage(
                                R.string.androidacy_test_mode_warning
                            )
                                .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                                    // User clicked OK button
                                    getSharedPreferences("mmm")!!
                                        .edit().putBoolean("androidacy_test_mode", true).apply()
                                    // Check the switch
                                    val mStartActivity =
                                        Intent(requireContext(), MainActivity::class.java)
                                    mStartActivity.flags =
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                    val mPendingIntentId = 123456
                                    // If < 23, FLAG_IMMUTABLE is not available
                                    val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                                        requireContext(),
                                        mPendingIntentId,
                                        mStartActivity,
                                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )
                                    val mgr =
                                        requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                                    mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                        mPendingIntent
                                    Timber.d(
                                        "Restarting app to save staging endpoint preference: %s",
                                        newValue
                                    )
                                    exitProcess(0) // Exit app process
                                }
                                .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                                    // User cancelled the dialog
                                    // Uncheck the switch
                                    val switchPreferenceCompat =
                                        androidacyTestMode as SwitchPreferenceCompat
                                    switchPreferenceCompat.isChecked = false
                                    // There's probably a better way to do this than duplicate code but I'm too lazy to figure it out
                                    getSharedPreferences("mmm")!!
                                        .edit().putBoolean("androidacy_test_mode", false).apply()
                                }.show()
                        } else {
                            getSharedPreferences("mmm")!!
                                .edit().putBoolean("androidacy_test_mode", false).apply()
                            // Show dialog to restart app with ok button
                            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.warning)
                                .setCancelable(false).setMessage(
                                R.string.androidacy_test_mode_disable_warning
                            )
                                .setNeutralButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                                    // User clicked OK button
                                    val mStartActivity =
                                        Intent(requireContext(), MainActivity::class.java)
                                    mStartActivity.flags =
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                    val mPendingIntentId = 123456
                                    // If < 23, FLAG_IMMUTABLE is not available
                                    val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                                        requireContext(),
                                        mPendingIntentId,
                                        mStartActivity,
                                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )
                                    val mgr =
                                        requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                                    mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                        mPendingIntent
                                    Timber.d(
                                        "Restarting app to save staging endpoint preference: %s",
                                        newValue
                                    )
                                    exitProcess(0) // Exit app process
                                }.show()
                        }
                        true
                    }
            }
            // Get magisk_alt_repo enabled state from room reposlist db
            val db = databaseBuilder(
                requireContext(),
                ReposListDatabase::class.java,
                "ReposList.db"
            ).allowMainThreadQueries().build()

            // add listener to magisk_alt_repo_enabled switch to update room db
            val magiskAltRepoEnabled =
                findPreference<Preference>("pref_magisk_alt_repo_enabled")!!
            magiskAltRepoEnabled.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                    // Update room db
                    db.reposListDao().setEnabled(
                        "magisk_alt_repo",
                        java.lang.Boolean.parseBoolean(newValue.toString())
                    )
                    true
                }
            // Disable toggling the pref_androidacy_repo_enabled on builds without an
            // ANDROIDACY_CLIENT_ID or where the ANDROIDACY_CLIENT_ID is empty
            val androidacyRepoEnabled =
                findPreference<SwitchPreferenceCompat>("pref_androidacy_repo_enabled")!!
            if (BuildConfig.ANDROIDACY_CLIENT_ID == "") {
                androidacyRepoEnabled.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener { _: Preference? ->
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.androidacy_repo_disabled)
                            .setCancelable(false).setMessage(
                            R.string.androidacy_repo_disabled_message
                        )
                            .setPositiveButton(R.string.download_full_app) { _: DialogInterface?, _: Int ->
                                // User clicked OK button. Open GitHub releases page
                                val browserIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.androidacy.com/downloads/?view=FoxMMM&utm_source=FoxMMM&utm_medium=app&utm_campaign=FoxMMM")
                                )
                                startActivity(browserIntent)
                            }.show()
                        // Revert the switch to off
                        androidacyRepoEnabled.isChecked = false
                        // Disable in room db
                        db.reposListDao().setEnabled("androidacy_repo", false)
                        false
                    }
            } else {
                // get if androidacy repo is enabled from room db
                val (_, _, androidacyRepoEnabledPref) = db.reposListDao().getById("androidacy_repo")
                // set the switch to the current state
                androidacyRepoEnabled.isChecked = androidacyRepoEnabledPref
                // add a click listener to the switch
                androidacyRepoEnabled.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        val enabled = androidacyRepoEnabled.isChecked
                        // save the new state
                        db.reposListDao().setEnabled("androidacy_repo", enabled)
                        true
                    }
                if (androidacyRepoEnabledPref) {
                    // get user role from AndroidacyRepoData.userInfo
                    val userInfo = AndroidacyRepoData.instance.userInfo
                    if (userInfo != null) {
                        val userRole = userInfo[0][1]
                        if (Objects.nonNull(userRole) && userRole != "Guest") {
                            // Disable the pref_androidacy_repo_api_donate preference
                            val prefAndroidacyRepoApiD =
                                findPreference<LongClickablePreference>("pref_androidacy_repo_donate")!!
                            prefAndroidacyRepoApiD.isEnabled = false
                            prefAndroidacyRepoApiD.setSummary(R.string.upgraded_summary)
                            prefAndroidacyRepoApiD.setTitle(R.string.upgraded)
                            prefAndroidacyRepoApiD.setIcon(R.drawable.baseline_check_24)
                        } else if (BuildConfig.FLAVOR == "play") {
                            // Disable the pref_androidacy_repo_api_token preference and hide the donate button
                            val prefAndroidacyRepoApiD =
                                findPreference<LongClickablePreference>("pref_androidacy_repo_donate")!!
                            prefAndroidacyRepoApiD.isEnabled = false
                            prefAndroidacyRepoApiD.isVisible = false
                        }
                    }
                    val originalApiKeyRef = arrayOf(
                        getSharedPreferences("androidacy")!!
                            .getString("pref_androidacy_api_token", "")
                    )
                    // Get the dummy pref_androidacy_repo_api_token preference with id pref_androidacy_repo_api_token
                    // we have to use the id because the key is different
                    val prefAndroidacyRepoApiKey =
                        findPreference<EditTextPreference>("pref_androidacy_repo_api_token")!!
                    // add validation to the EditTextPreference
                    // string must be 64 characters long, and only allows alphanumeric characters
                    prefAndroidacyRepoApiKey.setTitle(R.string.api_key)
                    prefAndroidacyRepoApiKey.setSummary(R.string.api_key_summary)
                    prefAndroidacyRepoApiKey.setDialogTitle(R.string.api_key)
                    prefAndroidacyRepoApiKey.setDefaultValue(originalApiKeyRef[0])
                    // Set the value to the current value
                    prefAndroidacyRepoApiKey.text = originalApiKeyRef[0]
                    prefAndroidacyRepoApiKey.isVisible = true
                    prefAndroidacyRepoApiKey.setOnBindEditTextListener { editText: EditText ->
                        editText.setSingleLine()
                        // Make the single line wrap
                        editText.setHorizontallyScrolling(false)
                        // Set the height to the maximum required to fit the text
                        editText.maxLines = Int.MAX_VALUE
                        // Make ok button say "Save"
                        editText.imeOptions = EditorInfo.IME_ACTION_DONE
                    }
                    prefAndroidacyRepoApiKey.setPositiveButtonText(R.string.save_api_key)
                    prefAndroidacyRepoApiKey.onPreferenceChangeListener =
                        Preference.OnPreferenceChangeListener setOnPreferenceChangeListener@{ _: Preference?, newValue: Any ->
                            // validate the api key client side first. should be 64 characters long, and only allow alphanumeric characters
                            if (!newValue.toString().matches("[a-zA-Z0-9]{64}".toRegex())) {
                                // Show snack bar with error
                                Snackbar.make(
                                    requireView(),
                                    R.string.api_key_mismatch,
                                    BaseTransientBottomBar.LENGTH_LONG
                                ).show()
                                // Restore the original api key
                                prefAndroidacyRepoApiKey.text = originalApiKeyRef[0]
                                prefAndroidacyRepoApiKey.performClick()
                                return@setOnPreferenceChangeListener false
                            }
                            // Make sure originalApiKeyRef is not null
                            if (originalApiKeyRef[0] == newValue) return@setOnPreferenceChangeListener true
                            // get original api key
                            val apiKey = newValue.toString()
                            // Show snack bar with indeterminate progress
                            Snackbar.make(
                                requireView(),
                                R.string.checking_api_key,
                                BaseTransientBottomBar.LENGTH_INDEFINITE
                            ).setAction(
                                R.string.cancel
                            ) {
                                // Restore the original api key
                                prefAndroidacyRepoApiKey.text = originalApiKeyRef[0]
                            }.show()
                            // Check the API key on a background thread
                            Thread(Runnable {
                                // If key is empty, just remove it and change the text of the snack bar
                                if (apiKey.isEmpty()) {
                                    getSharedPreferences("androidacy")!!.edit()
                                        .remove("pref_androidacy_api_token").apply()
                                    Handler(Looper.getMainLooper()).post {
                                        Snackbar.make(
                                            requireView(),
                                            R.string.api_key_removed,
                                            BaseTransientBottomBar.LENGTH_SHORT
                                        ).show()
                                        // Show dialog to restart app with ok button
                                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.restart)
                                            .setCancelable(false).setMessage(
                                                R.string.api_key_restart
                                            )
                                            .setNeutralButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                                                // User clicked OK button
                                                val mStartActivity = Intent(
                                                    requireContext(),
                                                    MainActivity::class.java
                                                )
                                                mStartActivity.flags =
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                                val mPendingIntentId = 123456
                                                // If < 23, FLAG_IMMUTABLE is not available
                                                val mPendingIntent: PendingIntent = PendingIntent.getActivity(
                                                    requireContext(),
                                                    mPendingIntentId,
                                                    mStartActivity,
                                                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                )
                                                val mgr =
                                                    requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                                                mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                                    mPendingIntent
                                                Timber.d(
                                                    "Restarting app to save token preference: %s",
                                                    newValue
                                                )
                                                exitProcess(0) // Exit app process
                                            }.show()
                                    }
                                } else {
                                    // If key < 64 chars, it's not valid
                                    if (apiKey.length < 64) {
                                        Handler(Looper.getMainLooper()).post {
                                            Snackbar.make(
                                                requireView(),
                                                R.string.api_key_invalid,
                                                BaseTransientBottomBar.LENGTH_SHORT
                                            ).show()
                                            // Save the original key
                                            getSharedPreferences("androidacy")!!
                                                .edit().putString(
                                                    "pref_androidacy_api_token",
                                                    originalApiKeyRef[0]
                                                ).apply()
                                            // Re-show the dialog with an error
                                            prefAndroidacyRepoApiKey.performClick()
                                            // Show error
                                            prefAndroidacyRepoApiKey.dialogMessage =
                                                getString(R.string.api_key_invalid)
                                        }
                                    } else {
                                        // If the key is the same as the original, just show a snack bar
                                        if (apiKey == originalApiKeyRef[0]) {
                                            Handler(Looper.getMainLooper()).post {
                                                Snackbar.make(
                                                    requireView(),
                                                    R.string.api_key_unchanged,
                                                    BaseTransientBottomBar.LENGTH_SHORT
                                                ).show()
                                            }
                                            return@Runnable
                                        }
                                        var valid = false
                                        try {
                                            valid = AndroidacyRepoData.instance.isValidToken(apiKey)
                                        } catch (ignored: IOException) {
                                        }
                                        // If the key is valid, save it
                                        if (valid) {
                                            originalApiKeyRef[0] = apiKey
                                            getINSTANCE()!!.androidacyRepoData!!.setToken(apiKey)
                                            getSharedPreferences("androidacy")!!
                                                .edit()
                                                .putString("pref_androidacy_api_token", apiKey)
                                                .apply()
                                            // Snackbar with success and restart button
                                            Handler(Looper.getMainLooper()).post {
                                                Snackbar.make(
                                                    requireView(),
                                                    R.string.api_key_valid,
                                                    BaseTransientBottomBar.LENGTH_SHORT
                                                ).show()
                                                // Show dialog to restart app with ok button
                                                MaterialAlertDialogBuilder(requireContext()).setTitle(
                                                    R.string.restart
                                                ).setCancelable(false).setMessage(
                                                    R.string.api_key_restart
                                                )
                                                    .setNeutralButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                                                        // User clicked OK button
                                                        val mStartActivity = Intent(
                                                            requireContext(),
                                                            MainActivity::class.java
                                                        )
                                                        mStartActivity.flags =
                                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                                                        val mPendingIntentId = 123456
                                                        // If < 23, FLAG_IMMUTABLE is not available
                                                        val mPendingIntent: PendingIntent =
                                                            PendingIntent.getActivity(
                                                            requireContext(),
                                                            mPendingIntentId,
                                                            mStartActivity,
                                                            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                        )
                                                        val mgr = requireContext().getSystemService(
                                                            ALARM_SERVICE
                                                        ) as AlarmManager
                                                        mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                                            mPendingIntent
                                                        Timber.d(
                                                            "Restarting app to save token preference: %s",
                                                            newValue
                                                        )
                                                        exitProcess(0) // Exit app process
                                                    }.show()
                                            }
                                        } else {
                                            Handler(Looper.getMainLooper()).post {
                                                Snackbar.make(
                                                    requireView(),
                                                    R.string.api_key_invalid,
                                                    BaseTransientBottomBar.LENGTH_SHORT
                                                ).show()
                                                // Save the original key
                                                INSTANCE!!.getSharedPreferences("androidacy", 0)
                                                    .edit().putString(
                                                        "pref_androidacy_api_token",
                                                        originalApiKeyRef[0]
                                                    ).apply()
                                                // Re-show the dialog with an error
                                                prefAndroidacyRepoApiKey.performClick()
                                                // Show error
                                                prefAndroidacyRepoApiKey.dialogMessage =
                                                    getString(R.string.api_key_invalid)
                                            }
                                        }
                                    }
                                }
                            }).start()
                            true
                        }
                }
            }
        }

        @SuppressLint("RestrictedApi")
        fun updateCustomRepoList(initial: Boolean) {
            // get all repos that are not built-in
            var custRepoEntries = 0
            // array of custom repos
            val customRepos = ArrayList<String>()
            val db = databaseBuilder(
                requireContext(),
                ReposListDatabase::class.java,
                "ReposList.db"
            ).allowMainThreadQueries().build()
            val reposList = db.reposListDao().getAll()
            for ((id) in reposList) {
                val buildInRepos = ArrayList(mutableListOf("androidacy_repo", "magisk_alt_repo"))
                if (!buildInRepos.contains(id)) {
                    custRepoEntries++
                    customRepos.add(id)
                }
            }
            Timber.d("%d repos: %s", custRepoEntries, customRepos)
            val customRepoManager = getINSTANCE()!!.customRepoManager
            for (i in 0 until custRepoEntries) {
                // get the id of the repo at current index in customRepos
                val repoData = customRepoManager!!.getRepo(customRepos[i])
                // convert repoData to a json string for logging
                Timber.d("RepoData for %d is %s", i, repoData.toJSON())
                setRepoData(repoData, "pref_custom_repo_$i")
                if (initial) {
                    val preference = findPreference<Preference>("pref_custom_repo_" + i + "_delete")
                        ?: continue
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener { preference1: Preference ->
                            db.reposListDao().delete(customRepos[i])
                            customRepoManager.removeRepo(i)
                            updateCustomRepoList(false)
                            preference1.isVisible = false
                            true
                        }
                }
            }
            // any custom repo prefs larger than the number of custom repos need to be hidden. max is 5
            // loop up until 5, and for each that's greater than the number of custom repos, hide it. we start at 0
            // if custom repos is zero, just hide them all
            if (custRepoEntries == 0) {
                for (i in 0..4) {
                    val preference = findPreference<Preference>("pref_custom_repo_$i")
                        ?: continue
                    preference.isVisible = false
                }
            } else {
                for (i in 0..4) {
                    val preference = findPreference<Preference>("pref_custom_repo_$i")
                        ?: continue
                    if (i >= custRepoEntries) {
                        preference.isVisible = false
                    }
                }
            }
            var preference = findPreference<Preference>("pref_custom_add_repo") ?: return
            preference.isVisible =
                customRepoManager!!.canAddRepo() && customRepoManager.repoCount < 5
            if (initial) { // Custom repo add button part.
                preference = findPreference("pref_custom_add_repo_button")!!
                if (preference == null) return
                preference.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        val context = requireContext()
                        val builder = MaterialAlertDialogBuilder(context)
                        val input = EditText(context)
                        input.setHint(R.string.custom_url)
                        input.setHorizontallyScrolling(true)
                        input.maxLines = 1
                        builder.setIcon(R.drawable.ic_baseline_add_box_24)
                        builder.setTitle(R.string.add_repo)
                        // make link in message clickable
                        builder.setMessage(R.string.add_repo_message)
                        builder.setView(input)
                        builder.setPositiveButton("OK") { _: DialogInterface?, _: Int ->
                            var text = input.text.toString()
                            text = text.trim { it <= ' ' }
                            // string should not be empty, start with https://, and not contain any spaces. http links are not allowed.
                            if (text.matches("^https://.*".toRegex()) && !text.contains(" ") && text.isNotEmpty()) {
                                if (customRepoManager.canAddRepo(text)) {
                                    val customRepoData = customRepoManager.addRepo(text)
                                    object : Thread("Add Custom Repo Thread") {
                                        override fun run() {
                                            try {
                                                customRepoData!!.quickPrePopulate()
                                                UiThreadHandler.handler.post {
                                                    updateCustomRepoList(
                                                        false
                                                    )
                                                }
                                            } catch (e: Exception) {
                                                Timber.e(e)
                                                // show new dialog
                                                Handler(Looper.getMainLooper()).post {
                                                    MaterialAlertDialogBuilder(context).setTitle(
                                                        R.string.error_adding
                                                    ).setMessage(e.message)
                                                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int -> }
                                                        .show()
                                                }
                                            }
                                        }
                                    }.start()
                                } else {
                                    Snackbar.make(
                                        requireView(),
                                        R.string.invalid_repo_url,
                                        BaseTransientBottomBar.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Snackbar.make(
                                    requireView(),
                                    R.string.invalid_repo_url,
                                    BaseTransientBottomBar.LENGTH_LONG
                                ).show()
                            }
                        }
                        builder.setNegativeButton("Cancel") { dialog: DialogInterface, _: Int -> dialog.cancel() }
                        builder.setNeutralButton("Docs") { _: DialogInterface?, _: Int ->
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Androidacy/MagiskModuleManager/blob/master/docs/DEVELOPERS.md#custom-repo-format")
                            )
                            startActivity(intent)
                        }
                        val alertDialog = builder.show()
                        val positiveButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE)
                        // validate as they type
                        input.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence,
                                start: Int,
                                count: Int,
                                after: Int
                            ) {
                            }

                            override fun onTextChanged(
                                charSequence: CharSequence,
                                start: Int,
                                before: Int,
                                count: Int
                            ) {
                                Timber.i("checking repo url validity")
                                // show error if string is empty, does not start with https://, or contains spaces
                                if (charSequence.toString().isEmpty()) {
                                    input.error = getString(R.string.empty_field)
                                    Timber.d("No input for repo")
                                    positiveButton.isEnabled = false
                                } else if (!charSequence.toString()
                                        .matches("^https://.*".toRegex())
                                ) {
                                    input.error = getString(R.string.invalid_repo_url)
                                    Timber.d("Non https link for repo")
                                    positiveButton.isEnabled = false
                                } else if (charSequence.toString().contains(" ")) {
                                    input.error = getString(R.string.invalid_repo_url)
                                    Timber.d("Repo url has space")
                                    positiveButton.isEnabled = false
                                } else if (!customRepoManager.canAddRepo(charSequence.toString())) {
                                    input.error = getString(R.string.repo_already_added)
                                    Timber.d("Could not add repo for misc reason")
                                    positiveButton.isEnabled = false
                                } else {
                                    // enable ok button
                                    Timber.d("Repo URL is ok")
                                    positiveButton.isEnabled = true
                                }
                            }

                            override fun afterTextChanged(s: Editable) {}
                        })
                        positiveButton.isEnabled = false
                        val dp10 = FoxDisplay.dpToPixel(10f)
                        val dp20 = FoxDisplay.dpToPixel(20f)
                        FoxViewCompat.setMargin(input, dp20, dp10, dp20, dp10)
                        true
                    }
            }
        }

        private fun setRepoData(url: String) {
            val repoData = getINSTANCE()!![url]
            setRepoData(
                repoData,
                "pref_" + if (repoData == null) internalIdOfUrl(url) else repoData.preferenceId
            )
        }

        private fun setRepoData(repoData: RepoData?, preferenceName: String) {
            if (repoData == null) return
            Timber.d("Setting preference $preferenceName to $repoData")
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            var preference = findPreference<Preference>(preferenceName) ?: return
            if (!preferenceName.contains("androidacy") && !preferenceName.contains("magisk_alt_repo")) {
                if (repoData != null) {
                    val db = databaseBuilder(
                        requireContext(),
                        ReposListDatabase::class.java,
                        "ReposList.db"
                    ).allowMainThreadQueries().build()
                    val reposList = db.reposListDao().getById(repoData.preferenceId!!)
                    Timber.d("Setting preference $preferenceName because it is not the Androidacy repo or the Magisk Alt Repo")
                    if (repoData.isForceHide || reposList == null) {
                        Timber.d("Hiding preference $preferenceName because it is null or force hidden")
                        hideRepoData(preferenceName)
                        return
                    } else {
                        Timber.d(
                            "Showing preference %s because the forceHide status is %s and the RealmResults is %s",
                            preferenceName,
                            repoData.isForceHide,
                            reposList
                        )
                        preference.title = repoData.name
                        preference.isVisible = true
                        // set website, support, and submitmodule as well as donate
                        if (repoData.getWebsite() != null) {
                            findPreference<Preference>(preferenceName + "_website")!!.onPreferenceClickListener =
                                Preference.OnPreferenceClickListener {
                                    openUrl(getFoxActivity(this), repoData.getWebsite())
                                    true
                                }
                        } else {
                            findPreference<Preference>(preferenceName + "_website")!!.isVisible =
                                false
                        }
                        if (repoData.getSupport() != null) {
                            findPreference<Preference>(preferenceName + "_support")!!.onPreferenceClickListener =
                                Preference.OnPreferenceClickListener {
                                    openUrl(getFoxActivity(this), repoData.getSupport())
                                    true
                                }
                        } else {
                            findPreference<Preference>("${preferenceName}_support")!!.isVisible =
                                false
                        }
                        if (repoData.getSubmitModule() != null) {
                            findPreference<Preference>(preferenceName + "_submit")!!.onPreferenceClickListener =
                                Preference.OnPreferenceClickListener {
                                    openUrl(getFoxActivity(this), repoData.getSubmitModule())
                                    true
                                }
                        } else {
                            findPreference<Preference>(preferenceName + "_submit")!!.isVisible =
                                false
                        }
                        if (repoData.getDonate() != null) {
                            findPreference<Preference>(preferenceName + "_donate")!!.onPreferenceClickListener =
                                Preference.OnPreferenceClickListener {
                                    openUrl(getFoxActivity(this), repoData.getDonate())
                                    true
                                }
                        } else {
                            findPreference<Preference>(preferenceName + "_donate")!!.isVisible =
                                false
                        }
                    }
                } else {
                    Timber.d("Hiding preference $preferenceName because it's data is null")
                    hideRepoData(preferenceName)
                    return
                }
            }
            preference = findPreference(preferenceName + "_enabled") ?: return
            if (preference != null) {
                // Handle custom repo separately
                if (repoData is CustomRepoData) {
                    preference.setTitle(R.string.custom_repo_always_on)
                    // Disable the preference
                    preference.isEnabled = false
                    return
                } else {
                    (preference as TwoStatePreference).isChecked = repoData.isEnabled
                    preference.setTitle(if (repoData.isEnabled) R.string.repo_enabled else R.string.repo_disabled)
                    preference.setOnPreferenceChangeListener { p: Preference, newValue: Any ->
                        p.setTitle(if (newValue as Boolean) R.string.repo_enabled else R.string.repo_disabled)
                        // Show snackbar telling the user to refresh the modules list or restart the app
                        Snackbar.make(
                            requireView(),
                            R.string.repo_enabled_changed,
                            BaseTransientBottomBar.LENGTH_LONG
                        ).show()
                        true
                    }
                }
            }
            preference = findPreference(preferenceName + "_website") ?: return
            val homepage = repoData.getWebsite()
            if (preference != null) {
                if (homepage.isNotEmpty()) {
                    preference.isVisible = true
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            openUrl(getFoxActivity(this), homepage)
                            true
                        }
                    (preference as LongClickablePreference).onPreferenceLongClickListener =
                        OnPreferenceLongClickListener {
                            val toastText = requireContext().getString(R.string.link_copied)
                            clipboard.setPrimaryClip(ClipData.newPlainText(toastText, homepage))
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            true
                        }
                } else {
                    preference.isVisible = false
                }
            }
            preference = findPreference(preferenceName + "_support") ?: return
            val supportUrl = repoData.getSupport()
            if (preference != null) {
                if (!supportUrl.isNullOrEmpty()) {
                    preference.isVisible = true
                    preference.setIcon(supportIconForUrl(supportUrl))
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            openUrl(getFoxActivity(this), supportUrl)
                            true
                        }
                    (preference as LongClickablePreference).onPreferenceLongClickListener =
                        OnPreferenceLongClickListener {
                            val toastText = requireContext().getString(R.string.link_copied)
                            clipboard.setPrimaryClip(ClipData.newPlainText(toastText, supportUrl))
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            true
                        }
                } else {
                    preference.isVisible = false
                }
            }
            preference = findPreference(preferenceName + "_donate") ?: return
            val donateUrl = repoData.getDonate()
            if (preference != null) {
                if (donateUrl != null) {
                    preference.isVisible = true
                    preference.setIcon(donateIconForUrl(donateUrl))
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            openUrl(getFoxActivity(this), donateUrl)
                            true
                        }
                    (preference as LongClickablePreference).onPreferenceLongClickListener =
                        OnPreferenceLongClickListener {
                            val toastText = requireContext().getString(R.string.link_copied)
                            clipboard.setPrimaryClip(ClipData.newPlainText(toastText, donateUrl))
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            true
                        }
                } else {
                    preference.isVisible = false
                }
            }
            preference = findPreference(preferenceName + "_submit") ?: return
            val submissionUrl = repoData.getSubmitModule()
            if (preference != null) {
                if (!submissionUrl.isNullOrEmpty()) {
                    preference.isVisible = true
                    preference.onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            openUrl(getFoxActivity(this), submissionUrl)
                            true
                        }
                    (preference as LongClickablePreference).onPreferenceLongClickListener =
                        OnPreferenceLongClickListener {
                            val toastText = requireContext().getString(R.string.link_copied)
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    toastText,
                                    submissionUrl
                                )
                            )
                            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                            true
                        }
                } else {
                    preference.isVisible = false
                }
            }
        }

        private fun hideRepoData(preferenceName: String) {
            val preference = findPreference<Preference>(preferenceName) ?: return
            preference.isVisible = false
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "mmm"
            setPreferencesFromResource(R.xml.repo_preferences, rootKey)
            applyMaterial3(preferenceScreen)
            setRepoData(RepoManager.MAGISK_ALT_REPO)
            setRepoData(RepoManager.ANDROIDACY_MAGISK_REPO_ENDPOINT)
            updateCustomRepoList(true)
            onCreatePreferencesAndroidacy()
        }

        companion object {
            /**
             * *says proudly*: I stole it
             *
             *
             * namely, from [neo wellbeing](https://github.com/NeoApplications/Neo-Wellbeing/blob/9fca4136263780c022f9ec6433c0b43d159166db/app/src/main/java/org/eu/droid_ng/wellbeing/prefs/SettingsActivity.java#L101)
             */
            fun applyMaterial3(p: Preference) {
                if (p is PreferenceGroup) {
                    for (i in 0 until p.preferenceCount) {
                        applyMaterial3(p.getPreference(i))
                    }
                }
                (p as? SwitchPreferenceCompat)?.widgetLayoutResource =
                    R.layout.preference_material_switch
            }
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    companion object {
        // Shamelessly adapted from https://github.com/DrKLO/Telegram/blob/2c71f6c92b45386f0c2b25f1442596462404bb39/TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java#L1254
        const val PERFORMANCE_CLASS_LOW = 0
        const val PERFORMANCE_CLASS_AVERAGE = 1
        const val PERFORMANCE_CLASS_HIGH = 2
        private const val LANGUAGE_SUPPORT_LEVEL = 1
        private var devModeStepFirstBootIgnore = isDeveloper
        private var devModeStep = 0

        @get:PerformanceClass
        val devicePerformanceClass: Int
            get() {
                // special algorithm to determine performance class. low is < 4 cores and/ore < 4GB ram, mid is 4-6 cores and 4-6GB ram, high is > 6 cores and > 6GB ram. android sdk version is used as well
                // device is awarded 1 point for each core and 1 point for each GB of ram.
                var points = 0
                val cores = Runtime.getRuntime().availableProcessors()
                val activityManager = INSTANCE!!.getSystemService(
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
                Timber.d("Device performance class: %d", points)
                return if (points <= 7) {
                    PERFORMANCE_CLASS_LOW
                } else if (points <= 12) {
                    PERFORMANCE_CLASS_AVERAGE
                } else {
                    PERFORMANCE_CLASS_HIGH
                }
            }
    }
}