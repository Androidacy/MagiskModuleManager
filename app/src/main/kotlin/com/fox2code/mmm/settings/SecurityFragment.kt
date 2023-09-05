package com.fox2code.mmm.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.utils.io.net.Http
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import kotlin.system.exitProcess

class SecurityFragment : PreferenceFragmentCompat() {
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

        setPreferencesFromResource(R.xml.security_preferences, rootKey)
        SettingsActivity.applyMaterial3(preferenceScreen)

        findPreference<Preference>("pref_dns_over_https")!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, v: Any? ->
                Http.setDoh(
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
                                requireContext().getSystemService(FoxActivity.ALARM_SERVICE) as AlarmManager
                            mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                mPendingIntent
                            if (BuildConfig.DEBUG) Timber.d("Restarting app to save showcase mode preference: %s", v)
                            exitProcess(0) // Exit app process
                        }.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int ->
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
                                requireContext().getSystemService(FoxActivity.ALARM_SERVICE) as AlarmManager
                            mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                mPendingIntent
                            if (BuildConfig.DEBUG) Timber.d("Restarting app to save showcase mode preference: %s", v)
                            exitProcess(0) // Exit app process
                        }.show()
                }
                true
            }
    }
}