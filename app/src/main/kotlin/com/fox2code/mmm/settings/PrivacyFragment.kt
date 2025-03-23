package com.fox2code.mmm.settings

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import kotlin.system.exitProcess

class PrivacyFragment : PreferenceFragmentCompat() {
    @SuppressLint("CommitPrefEdits")
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
        setPreferencesFromResource(R.xml.privacy_preferences, rootKey)
        // Crash reporting
        val crashReportingPreference =
            findPreference<SwitchPreferenceCompat>("pref_crash_reporting")
        crashReportingPreference!!.isChecked = MainApplication.isCrashReportingEnabled
        val initialValue: Any = MainApplication.isCrashReportingEnabled
        crashReportingPreference.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener setOnPreferenceChangeListener@{ _: Preference?, newValue: Any ->
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
                    val mgr =
                        requireContext().getSystemService(AppCompatActivity.ALARM_SERVICE) as AlarmManager
                    mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] = mPendingIntent
                    if (MainApplication.forceDebugLogging) Timber.d("Restarting app to save crash reporting preference: %s", newValue)
                    exitProcess(0) // Exit app process
                }
                // reverse the change if the user cancels the dialog
                materialAlertDialogBuilder.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int ->
                    crashReportingPreference.isChecked = initialValue as Boolean
                }
                materialAlertDialogBuilder.show()
                true
            }
        // on pref_analytics_enabled change, update pref_crash_reporting (switch must be off and disabled if analytics is off)
        val analyticsPreference = findPreference<SwitchPreferenceCompat>("pref_analytics_enabled")
        analyticsPreference!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any? ->
                @Suppress("NAME_SHADOWING") val newValue = newValue as Boolean
                crashReportingPreference.isEnabled = newValue
                if (!newValue) {
                    crashReportingPreference.isChecked = false
                    crashReportingPreference.isEnabled = false
                }
                // restart dialog
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
                    val mgr =
                        requireContext().getSystemService(AppCompatActivity.ALARM_SERVICE) as AlarmManager
                    mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] = mPendingIntent
                    if (MainApplication.forceDebugLogging) Timber.d("Restarting app to save analytics preference: %s", newValue)
                    exitProcess(0) // Exit app process
                }
                // reverse the change if the user cancels the dialog
                materialAlertDialogBuilder.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int ->
                    analyticsPreference.isChecked = initialValue as Boolean
                    crashReportingPreference.isEnabled = initialValue
                }
                materialAlertDialogBuilder.show()
                true
            }
        // now, disable pref_crash_reporting if analytics is off
        crashReportingPreference.isEnabled = analyticsPreference.isChecked
    }
}