package com.fox2code.mmm.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.utils.IntentHelper
import timber.log.Timber

@Suppress("KotlinConstantConditions")
class InfoFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val name = "mmmx"
        val context: Context? = MainApplication.getInstance()
        val masterKey: MasterKey
        val preferenceManager = preferenceManager
        val dataStore: SharedPreferenceDataStore
        try {
            masterKey =
                MasterKey.Builder(context!!).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
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

        setPreferencesFromResource(R.xml.app_info_preferences, rootKey)

        val clipboard =
            requireContext().getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
        var linkClickable: LongClickablePreference?
        if (BuildConfig.DEBUG || BuildConfig.ENABLE_AUTO_UPDATER) {
            linkClickable = findPreference("pref_report_bug")
            linkClickable!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { p: Preference ->
                    IntentHelper.openUrl(
                        p.context, "https://github.com/Androidacy/MagiskModuleManager/issues"
                    )
                    true
                }
            linkClickable.onPreferenceLongClickListener =
                LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText, "https://github.com/Androidacy/MagiskModuleManager/issues"
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
        if (MainApplication.forceDebugLogging) Timber.d("userRepo: %s", userRepo)

        // finalUserRepo is the user/repo part of REMOTE_URL
        // get everything after .com/ or .org/ or .io/ or .me/ or .net/ or .xyz/ or .tk/ or .co/ minus .git
        val finalUserRepo = userRepo.replace(
            "^(https?://)?(www\\.)?(github\\.com|gitlab\\.com|bitbucket\\.org|git\\.io|git\\.me|git\\.net|git\\.xyz|git\\.tk|git\\.co)/".toRegex(),
            ""
        )
        linkClickable!!.summary = String.format(
            getString(R.string.source_code_summary), BuildConfig.COMMIT_HASH, finalUserRepo
        )
        if (MainApplication.forceDebugLogging) Timber.d("finalUserRepo: %s", finalUserRepo)
        val finalUserRepo1 = userRepo
        linkClickable.onPreferenceClickListener =
            Preference.OnPreferenceClickListener setOnPreferenceClickListener@{ p: Preference ->
                // build url from BuildConfig.REMOTE_URL and BuildConfig.COMMIT_HASH. May have to remove the .git at the end
                IntentHelper.openUrl(
                    p.context,
                    finalUserRepo1.replace(".git", "") + "/tree/" + BuildConfig.COMMIT_HASH
                )
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                val toastText = requireContext().getString(R.string.link_copied)
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        toastText, BuildConfig.REMOTE_URL + "/tree/" + BuildConfig.COMMIT_HASH
                    )
                )
                Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                true
            }


        val prefDonateFox = findPreference<LongClickablePreference>("pref_donate_fox")
        if (BuildConfig.FLAVOR != "play") {
            prefDonateFox!!.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    // open fox
                    IntentHelper.openUrl(
                        MainApplication.getInstance().lastActivity!!, "https://paypal.me/fox2code"
                    )
                    true
                }
            // handle long click on pref_donate_fox
            prefDonateFox.onPreferenceLongClickListener =
                LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                    // copy to clipboard
                    val toastText = requireContext().getString(R.string.link_copied)
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            toastText, "https://paypal.me/fox2code"
                        )
                    )
                    Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                    true
                }
        } else {
            prefDonateFox!!.isVisible = false
        }
        // now handle pref_donate_androidacy
        val prefDonateAndroidacy = findPreference<LongClickablePreference>("pref_donate_androidacy")
        if (BuildConfig.FLAVOR != "play") {
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == "Guest" || AndroidacyRepoData.instance.memberLevel == null) {
                prefDonateAndroidacy!!.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener { _: Preference? ->
                        // copy FOX2CODE promo code to clipboard and toast user that they can use it for half off any subscription
                        val toastText = requireContext().getString(R.string.promo_code_copied)
                        clipboard.setPrimaryClip(ClipData.newPlainText(toastText, "FOX2CODE"))
                        Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                        // open androidacy
                        IntentHelper.openUrl(
                            MainApplication.getInstance().lastActivity!!,
                            "https://www.androidacy.com/membership-join/?utm_source=AMMM&utm_medium=app&utm_campaign=donate"
                        )
                        true
                    }
                // handle long click on pref_donate_androidacy
                prefDonateAndroidacy.onPreferenceLongClickListener =
                    LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                        // copy to clipboard
                        val toastText = requireContext().getString(R.string.link_copied)
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                toastText,
                                "https://www.androidacy.com/membership-join/?utm_source=AMMM&utm_medium=app&utm_campaign=donate"
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

        linkClickable = findPreference("pref_support")
        linkClickable!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { p: Preference ->
                IntentHelper.openUrl(p.context, "https://t.me/androidacy_discussions")
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                val toastText = requireContext().getString(R.string.link_copied)
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        toastText, "https://t.me/androidacy_discussions"
                    )
                )
                Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                true
            }
        // pref_announcements to https://t.me/androidacy
        linkClickable = findPreference("pref_announcements")
        linkClickable!!.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { p: Preference ->
                IntentHelper.openUrl(p.context, "https://t.me/androidacy")
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
                val toastText = requireContext().getString(R.string.link_copied)
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        toastText, "https://t.me/androidacy"
                    )
                )
                Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
                true
            }
    }
}
