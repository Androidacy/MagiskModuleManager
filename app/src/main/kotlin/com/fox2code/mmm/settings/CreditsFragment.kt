package com.fox2code.mmm.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.utils.IntentHelper
import timber.log.Timber

class CreditsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

        val name = "mmmx"
        val context: Context? = MainApplication.INSTANCE
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

        setPreferencesFromResource(R.xml.credits_preferences, rootKey)
        SettingsActivity.applyMaterial3(preferenceScreen)



        val clipboard = requireContext().getSystemService(FoxActivity.CLIPBOARD_SERVICE) as ClipboardManager

        // pref_contributors should lead to the contributors page
        var linkClickable: LongClickablePreference? = findPreference("pref_contributors")
        linkClickable!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { p: Preference ->
                // Remove the .git if it exists and add /graphs/contributors
                var url = BuildConfig.REMOTE_URL
                if (url.endsWith(".git")) {
                    url = url.substring(0, url.length - 4)
                }
                url += "/graphs/contributors"
                IntentHelper.openUrl(p.context, url)
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
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


        // Next, the pref_androidacy_thanks should lead to the androidacy website
        linkClickable = findPreference("pref_androidacy_thanks")
        linkClickable!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { p: Preference ->
                IntentHelper.openUrl(
                    p.context,
                    "https://www.androidacy.com?utm_source=FoxMagiskModuleManager&utm_medium=app&utm_campaign=FoxMagiskModuleManager"
                )
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
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
                IntentHelper.openUrl(p.context, "https://github.com/Fox2Code")
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                val toastText = requireContext().getString(R.string.link_copied)
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        toastText, "https://github.com/Fox2Code"
                    )
                )
                Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                true
            }
    }

}