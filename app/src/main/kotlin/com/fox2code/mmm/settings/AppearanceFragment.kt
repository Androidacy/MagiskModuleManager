package com.fox2code.mmm.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.rosettax.LanguageSwitcher
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.internal.UiThreadHandler
import timber.log.Timber

class AppearanceFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val name = "mmmx"
        val context: Context? = MainApplication.INSTANCE
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
        setPreferencesFromResource(R.xml.theme_preferences, rootKey)
        val themePreference = findPreference<ListPreference>("pref_theme")
        // If transparent theme(s) are set, disable monet
        if (themePreference!!.value == "transparent_light") {
            if (MainApplication.forceDebugLogging) Timber.d("disabling monet")
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
            Preference.SummaryProvider { _: Preference? -> themePreference.entry }
        themePreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                if (MainApplication.forceDebugLogging) Timber.d("refreshing activity. New value: %s", newValue)
                editor.putString("pref_theme", newValue as String).apply()
                // If theme contains "transparent" then disable monet
                if (newValue.toString().contains("transparent")) {
                    if (MainApplication.forceDebugLogging) Timber.d("disabling monet")
                    // Show a dialogue warning the user about issues with transparent themes and
                    // that blur/monet will be disabled
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.transparent_theme_dialogue_title)
                        .setMessage(
                            R.string.transparent_theme_dialogue_message
                        ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
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
                            UiThreadHandler.handler.postDelayed({
                                MainApplication.INSTANCE!!.updateTheme()
                            }, 1)
                            val intent = Intent(requireContext(), SettingsActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            startActivity(intent)
                        }.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int ->
                            // Revert to system theme
                            (findPreference<Preference>("pref_theme") as ListPreference?)!!.value =
                                "system"
                            // Refresh activity
                        }.show()
                } else {
                    findPreference<Preference>("pref_enable_monet")!!.isEnabled = true
                    findPreference<Preference>("pref_enable_monet")?.summary = ""
                    findPreference<Preference>("pref_enable_blur")!!.isEnabled = true
                    findPreference<Preference>("pref_enable_blur")?.summary = ""
                }
                UiThreadHandler.handler.postDelayed({
                    MainApplication.INSTANCE!!.updateTheme()
                    MainApplication.INSTANCE!!.lastActivity!!
                }, 1)
                true
            }

        val disableMonet = findPreference<Preference>("pref_enable_monet")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            disableMonet!!.setSummary(R.string.require_android_12)
            disableMonet.isEnabled = false
        }
        disableMonet!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            UiThreadHandler.handler.postDelayed({
                MainApplication.INSTANCE!!.updateTheme()
            }, 1)
            true
        }


        val enableBlur = findPreference<Preference>("pref_enable_blur")
        // Disable blur on low performance devices
        if (SettingsActivity.devicePerformanceClass < SettingsActivity.PERFORMANCE_CLASS_AVERAGE) {
            // Show a warning
            enableBlur!!.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                    if (newValue == true) {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.low_performance_device_dialogue_title)
                            .setMessage(
                                R.string.low_performance_device_dialogue_message
                            ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
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


        // Handle pref_language_selector_cta by taking user to https://translate.nift4.org/engage/foxmmm/
        val languageSelectorCta =
            findPreference<LongClickablePreference>("pref_language_selector_cta")
        languageSelectorCta!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { _: Preference? ->
                val browserIntent = Intent(
                    Intent.ACTION_VIEW, Uri.parse("https://translate.nift4.org/engage/foxmmm/")
                )
                startActivity(browserIntent)
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

        // Long click to copy url
        languageSelectorCta.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                val clipboard =
                    requireContext().getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
                val clip =
                    ClipData.newPlainText("URL", "https://translate.nift4.org/engage/foxmmm/")
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT)
                    .show()
                true
            }

        val translatedBy = this.getString(R.string.language_translated_by)
        // I don't "translate" english
        if (!("Translated by Fox2Code (Put your name here)" == translatedBy || "Translated by Fox2Code" == translatedBy)) {
            languageSelector.setSummary(R.string.language_translated_by)
        } else {
            languageSelector.summary = null
        }
    }
}