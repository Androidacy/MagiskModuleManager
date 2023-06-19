/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources.Theme
import android.os.Bundle
import android.os.Process
import android.view.View
import android.webkit.CookieManager
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.databinding.ActivitySetupBinding
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.room.ModuleListCacheDatabase
import com.fox2code.mmm.utils.room.ReposListDatabase
import com.fox2code.rosettax.LanguageActivity
import com.fox2code.rosettax.LanguageSwitcher
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.topjohnwu.superuser.internal.UiThreadHandler
import org.apache.commons.io.FileUtils
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Objects

class SetupActivity : FoxActivity(), LanguageActivity {
    private var cachedTheme = 0

    @SuppressLint("ApplySharedPref", "RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.setTitle(R.string.setup_title)
        this.window.navigationBarColor = this.getColor(R.color.black_transparent)
        createFiles()
        disableUpdateActivityForFdroidFlavor()
        // Set theme
        val prefs = MainApplication.getSharedPreferences("mmm")!!
        when (prefs.getString("theme", "system")) {
            "light" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Light)
            "dark" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Dark)
            "system" -> setTheme(R.style.Theme_MagiskModuleManager_Monet)
            "black" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Black)
            "transparent_light" -> setTheme(R.style.Theme_MagiskModuleManager_Transparent_Light)
        }
        val binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val view: View = binding.root
        (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_background_update_check)) as MaterialSwitch).isChecked =
            BuildConfig.ENABLE_AUTO_UPDATER
        (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting)) as MaterialSwitch).isChecked =
            BuildConfig.DEFAULT_ENABLE_CRASH_REPORTING
        // pref_crash_reporting_pii
        (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting_pii)) as MaterialSwitch).isChecked =
            BuildConfig.DEFAULT_ENABLE_CRASH_REPORTING_PII
        // pref_analytics_enabled
        (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_app_analytics)) as MaterialSwitch).isChecked =
            BuildConfig.DEFAULT_ENABLE_ANALYTICS
        // assert that both switches match the build config on debug builds
        if (BuildConfig.DEBUG) {
            assert((Objects.requireNonNull<Any>(view.findViewById(R.id.setup_background_update_check)) as MaterialSwitch).isChecked == BuildConfig.ENABLE_AUTO_UPDATER)
            assert((Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting)) as MaterialSwitch).isChecked == BuildConfig.DEFAULT_ENABLE_CRASH_REPORTING)
        }
        // Repos are a little harder, as the enabled_repos build config is an arraylist
        val andRepoView =
            Objects.requireNonNull<Any>(view.findViewById(R.id.setup_androidacy_repo)) as MaterialSwitch
        val magiskAltRepoView =
            Objects.requireNonNull<Any>(view.findViewById(R.id.setup_magisk_alt_repo)) as MaterialSwitch
        andRepoView.isChecked = BuildConfig.ENABLED_REPOS.contains("androidacy_repo")
        magiskAltRepoView.isChecked = BuildConfig.ENABLED_REPOS.contains("magisk_alt_repo")
        // On debug builds, log when a switch is toggled
        if (BuildConfig.DEBUG) {
            (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_background_update_check)) as MaterialSwitch).setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                Timber.i(
                    "Automatic update Check: %s",
                    isChecked
                )
            }
            (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting)) as MaterialSwitch).setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                Timber.i(
                    "Crash Reporting: %s",
                    isChecked
                )
            }
            (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting_pii)) as MaterialSwitch).setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                Timber.i(
                    "Crash Reporting PII: %s",
                    isChecked
                )
            }
            (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_androidacy_repo)) as MaterialSwitch).setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                Timber.i(
                    "Androidacy Repo: %s",
                    isChecked
                )
            }
            (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_magisk_alt_repo)) as MaterialSwitch).setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
                Timber.i(
                    "Magisk Alt Repo: %s",
                    isChecked
                )
            }
        }
        // Setup popup dialogue for the setup_theme_button
        val themeButton = view.findViewById<MaterialButton>(R.id.setup_theme_button)
        themeButton.setOnClickListener { _: View? ->
            // Create a new dialog for the theme picker
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.setup_theme_title)
            // Create a new array of theme names (system, light, dark, black, transparent light)
            val themeNames = arrayOf(
                getString(R.string.theme_system), getString(R.string.theme_light), getString(
                    R.string.theme_dark
                ), getString(R.string.theme_black), getString(R.string.theme_transparent_light)
            )
            // Create a new array of theme values (system, light, dark, black, transparent_light)
            val themeValues = arrayOf("system", "light", "dark", "black", "transparent_light")
            // if pref_theme is set, check the relevant theme_* menu item, otherwise check the default (theme_system)
            val prefTheme = prefs.getString("pref_theme", "system")
            var checkedItem = 0
            when (prefTheme) {
                "system" -> {}
                "light" -> checkedItem = 1
                "dark" -> checkedItem = 2
                "black" -> checkedItem = 3
                "transparent_light" -> checkedItem = 4
            }
            builder.setCancelable(true)
            // Create the dialog
            builder.setSingleChoiceItems(
                themeNames,
                checkedItem
            ) { dialog: DialogInterface, which: Int ->
                // Set the theme
                prefs.edit().putString("pref_theme", themeValues[which]).commit()
                // Dismiss the dialog
                dialog.dismiss()
                // Set the theme
                UiThreadHandler.handler.postDelayed({
                    when (prefs.getString("pref_theme", "system")) {
                        "light" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Light)
                        "dark" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Dark)
                        "system" -> setTheme(R.style.Theme_MagiskModuleManager_Monet)
                        "black" -> setTheme(R.style.Theme_MagiskModuleManager_Monet_Black)
                        "transparent_light" -> setTheme(R.style.Theme_MagiskModuleManager_Transparent_Light)
                    }
                    // restart the activity because switching to transparent pisses the rendering engine off
                    val intent = Intent(this, SetupActivity::class.java)
                    finish()
                    // ensure intent originates from the same package
                    intent.setPackage(packageName)
                    startActivity(intent)
                }, 100)
            }
            builder.show()
        }
        // Setup language selector
        val languageSelector = view.findViewById<MaterialButton>(R.id.setup_language_button)
        languageSelector.setOnClickListener { _: View? ->
            val ls = IntentHelper.getActivity(this)?.let { LanguageSwitcher(it) }
            ls?.setSupportedStringLocales(MainApplication.supportedLocales)
            ls?.showChangeLanguageDialog(IntentHelper.getActivity(this) as FragmentActivity)
        }
        // Set up the buttons
        // Setup button
        val setupButton = view.findViewById<BottomNavigationItemView>(R.id.setup_finish)
        // on clicking setup_agree_eula, enable the setup button if it's checked, if it's not, disable it
        val agreeEula = view.findViewById<MaterialCheckBox>(R.id.setup_agree_eula)
        agreeEula.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            setupButton.isEnabled = isChecked
        }
        setupButton.setOnClickListener { _: View? ->
            Timber.i("Setup button clicked")
            // get instance of editor
            Timber.d("Saving preferences")
            val editor = prefs.edit()
            Timber.d("Got editor: %s", editor)
            // Set the Automatic update check pref
            editor.putBoolean(
                "pref_background_update_check", (Objects.requireNonNull<Any>(
                    view.findViewById(
                        R.id.setup_background_update_check
                    )
                ) as MaterialSwitch).isChecked
            )
            // require wifi pref
            editor.putBoolean(
                "pref_background_update_check_wifi", (Objects.requireNonNull<Any>(
                    view.findViewById(
                        R.id.setup_background_update_check_require_wifi
                    )
                ) as MaterialSwitch).isChecked
            )
            // Set the crash reporting pref
            editor.putBoolean(
                "pref_crash_reporting",
                (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_crash_reporting)) as MaterialSwitch).isChecked
            )
            // Set the crash reporting PII pref
            editor.putBoolean(
                "pref_crash_reporting_pii", (Objects.requireNonNull<Any>(
                    view.findViewById(
                        R.id.setup_crash_reporting_pii
                    )
                ) as MaterialSwitch).isChecked
            )
            editor.putBoolean(
                "pref_analytics_enabled",
                (Objects.requireNonNull<Any>(view.findViewById(R.id.setup_app_analytics)) as MaterialSwitch).isChecked
            )
            Timber.d("Saving preferences")
            // now basically do the same thing for room db
            val db = Room.databaseBuilder(
                applicationContext,
                ReposListDatabase::class.java, "ReposList.db"
            ).build()
            val androidacyRepoRoom = andRepoView.isChecked
            val magiskAltRepoRoom = magiskAltRepoView.isChecked
            val reposListDao = db.reposListDao()
            val androidacyRepoRoomObj = reposListDao.getById("androidacy_repo")
            val magiskAltRepoRoomObj = reposListDao.getById("magisk_alt_repo")
            reposListDao.setEnabled(androidacyRepoRoomObj.id, androidacyRepoRoom)
            reposListDao.setEnabled(magiskAltRepoRoomObj.id, magiskAltRepoRoom)
            db.close()
            editor.putString("last_shown_setup", "v3")
            // Commit the changes
            editor.commit()
            // sleep to allow the realm transaction to finish
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // Log the changes
            Timber.d("Setup finished. Preferences: %s", prefs.all)
            Timber.d("Androidacy repo: %s", androidacyRepoRoom)
            Timber.d("Magisk Alt repo: %s", magiskAltRepoRoom)
            // log last shown setup
            Timber.d("Last shown setup: %s", prefs.getString("last_shown_setup", "v0"))
            // Restart the activity
            MainActivity.doSetupRestarting = true
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            try {
                pendingIntent.send()
            } catch (e: PendingIntent.CanceledException) {
                Timber.e(e)
            }
            Process.killProcess(Process.myPid())
        }
        // Cancel button
        val cancelButton = view.findViewById<BottomNavigationItemView>(R.id.cancel_setup)
        // unselect the cancel button because it's selected by default
        cancelButton.isSelected = false
        cancelButton.setOnClickListener { _: View? ->
            Timber.i("Cancel button clicked")
            // close the app
            finish()
        }
    }

    override fun getTheme(): Theme {
        val theme = super.getTheme()
        // try cached value
        if (cachedTheme != 0) {
            theme.applyStyle(cachedTheme, true)
            return theme
        }
        // Set the theme
        val prefs = MainApplication.getSharedPreferences("mmm")!!
        when (prefs.getString("pref_theme", "system")) {
            "light" -> {
                theme.applyStyle(R.style.Theme_MagiskModuleManager_Monet_Light, true)
                cachedTheme = R.style.Theme_MagiskModuleManager_Monet_Light
            }

            "dark" -> {
                theme.applyStyle(R.style.Theme_MagiskModuleManager_Monet_Dark, true)
                cachedTheme = R.style.Theme_MagiskModuleManager_Monet_Dark
            }

            "system" -> {
                theme.applyStyle(R.style.Theme_MagiskModuleManager_Monet, true)
                cachedTheme = R.style.Theme_MagiskModuleManager_Monet
            }

            "black" -> {
                theme.applyStyle(R.style.Theme_MagiskModuleManager_Monet_Black, true)
                cachedTheme = R.style.Theme_MagiskModuleManager_Monet_Black
            }

            "transparent_light" -> {
                theme.applyStyle(R.style.Theme_MagiskModuleManager_Transparent_Light, true)
                cachedTheme = R.style.Theme_MagiskModuleManager_Transparent_Light
            }
        }
        return theme
    }

    @SuppressLint("InlinedApi", "RestrictedApi")
    override fun refreshRosettaX() {
        // refresh app language
        runOnUiThread {

            // refresh activity
            val intent = Intent(this, SetupActivity::class.java)
            finish()
            startActivity(intent)
        }
    }

    // creates the room database
    private fun createDatabases() {
        val startTime = System.currentTimeMillis()
        val appContext = MainApplication.INSTANCE!!.applicationContext
        Room.databaseBuilder(appContext, ReposListDatabase::class.java, "reposlist.db")
            .createFromAsset("assets/reposlist.db")
            .fallbackToDestructiveMigration()
            .build()
        // same for modulelistcache
        Room.databaseBuilder(appContext, ModuleListCacheDatabase::class.java, "modulelistcache.db")
            .createFromAsset("assets/modulelistcache.db")
            .fallbackToDestructiveMigration()
            .build()
        Timber.d("Databases created in %s ms", System.currentTimeMillis() - startTime)
    }

    private fun createFiles() {
        // use cookiemanager to create the cookie database
        try {
            CookieManager.getInstance()
        } catch (e: Exception) {
            Timber.e(e)
            // show a toast
            runOnUiThread {
                Toast.makeText(
                    this,
                    R.string.error_creating_cookie_database,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // we literally only use these to create the http cache folders
        try {
            FileUtils.forceMkdir(File(MainApplication.INSTANCE!!.dataDir.toString() + "/cache/cronet"))
            FileUtils.forceMkdir(File(MainApplication.INSTANCE!!.dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/wasm"))
            FileUtils.forceMkdir(File(MainApplication.INSTANCE!!.dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/js"))
        } catch (e: IOException) {
            Timber.e(e)
        }
        createDatabases()
    }

    @Suppress("KotlinConstantConditions")
    private fun disableUpdateActivityForFdroidFlavor() {
        if (BuildConfig.FLAVOR == "fdroid" || BuildConfig.FLAVOR == "play") {
            // check if the update activity is enabled
            val pm = packageManager
            val componentName = ComponentName(this, UpdateActivity::class.java)
            val componentEnabledSetting = pm.getComponentEnabledSetting(componentName)
            if (componentEnabledSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                Timber.d("Disabling update activity for fdroid flavor")
                // disable update activity through package manager
                pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}