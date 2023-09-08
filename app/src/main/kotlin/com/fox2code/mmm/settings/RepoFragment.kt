package com.fox2code.mmm.settings

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.ALARM_SERVICE
import android.content.Context.CLIPBOARD_SERVICE
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import androidx.room.Room
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.foxcompat.view.FoxViewCompat
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.module.ActionButtonType
import com.fox2code.mmm.repo.CustomRepoData
import com.fox2code.mmm.repo.RepoData
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.room.ReposListDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.internal.UiThreadHandler
import timber.log.Timber
import java.io.IOException
import java.util.Objects
import kotlin.system.exitProcess

@Suppress("SENSELESS_COMPARISON")
class RepoFragment : PreferenceFragmentCompat() {


    @SuppressLint("RestrictedApi", "UnspecifiedImmutableFlag")
    fun onCreatePreferencesAndroidacy() {
        // Bind the pref_show_captcha_webview to captchaWebview('https://production-api.androidacy.com/')
        // Also require dev mode
        // CaptchaWebview.setVisible(false);
        val androidacyTestMode =
            findPreference<Preference>("pref_androidacy_test_mode")!!
        if (!MainApplication.isDeveloper) {
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
                                MainApplication.getSharedPreferences("mmm")!!
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
                                if (BuildConfig.DEBUG) Timber.d(
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
                                MainApplication.getSharedPreferences("mmm")!!
                                    .edit().putBoolean("androidacy_test_mode", false).apply()
                            }.show()
                    } else {
                        MainApplication.getSharedPreferences("mmm")!!
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
                                if (BuildConfig.DEBUG) Timber.d(
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
        val db = Room.databaseBuilder(
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
                MainApplication.INSTANCE!!.repoModules.clear()
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
                    MainApplication.INSTANCE!!.repoModules.clear()
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
                    MainApplication.getSharedPreferences("androidacy")!!
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
                                MainApplication.getSharedPreferences("androidacy")!!.edit()
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
                                            val mPendingIntent: PendingIntent =
                                                PendingIntent.getActivity(
                                                    requireContext(),
                                                    mPendingIntentId,
                                                    mStartActivity,
                                                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                )
                                            val mgr =
                                                requireContext().getSystemService(ALARM_SERVICE) as AlarmManager
                                            mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] =
                                                mPendingIntent
                                            if (BuildConfig.DEBUG) Timber.d(
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
                                        MainApplication.getSharedPreferences("androidacy")!!
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
                                        RepoManager.getINSTANCE()!!.androidacyRepoData!!.setToken(
                                            apiKey
                                        )
                                        MainApplication.getSharedPreferences("androidacy")!!
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
                                                    if (BuildConfig.DEBUG) Timber.d(
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
                                            MainApplication.INSTANCE!!.getSharedPreferences(
                                                "androidacy",
                                                0
                                            )
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
        val db = Room.databaseBuilder(
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
        if (BuildConfig.DEBUG) Timber.d("%d repos: %s", custRepoEntries, customRepos)
        val customRepoManager = RepoManager.getINSTANCE()!!.customRepoManager
        for (i in 0 until custRepoEntries) {
            // get the id of the repo at current index in customRepos
            val repoData = customRepoManager!!.getRepo(customRepos[i])
            // convert repoData to a json string for logging
            if (BuildConfig.DEBUG) Timber.d("RepoData for %d is %s", i, repoData.toJSON())
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
                                if (BuildConfig.DEBUG) Timber.d("No input for repo")
                                positiveButton.isEnabled = false
                            } else if (!charSequence.toString()
                                    .matches("^https://.*".toRegex())
                            ) {
                                input.error = getString(R.string.invalid_repo_url)
                                if (BuildConfig.DEBUG) Timber.d("Non https link for repo")
                                positiveButton.isEnabled = false
                            } else if (charSequence.toString().contains(" ")) {
                                input.error = getString(R.string.invalid_repo_url)
                                if (BuildConfig.DEBUG) Timber.d("Repo url has space")
                                positiveButton.isEnabled = false
                            } else if (!customRepoManager.canAddRepo(charSequence.toString())) {
                                input.error = getString(R.string.repo_already_added)
                                if (BuildConfig.DEBUG) Timber.d("Could not add repo for misc reason")
                                positiveButton.isEnabled = false
                            } else {
                                // enable ok button
                                if (BuildConfig.DEBUG) Timber.d("Repo URL is ok")
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
        val repoData = RepoManager.getINSTANCE()!![url]
        setRepoData(
            repoData,
            "pref_" + if (repoData == null) RepoManager.internalIdOfUrl(url) else repoData.preferenceId
        )
    }

    private fun setRepoData(repoData: RepoData?, preferenceName: String) {
        if (repoData == null) return
        if (BuildConfig.DEBUG) Timber.d("Setting preference $preferenceName to $repoData")
        val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        var preference = findPreference<Preference>(preferenceName) ?: return
        if (!preferenceName.contains("androidacy") && !preferenceName.contains("magisk_alt_repo")) {
            if (repoData != null) {
                val db = Room.databaseBuilder(
                    requireContext(),
                    ReposListDatabase::class.java,
                    "ReposList.db"
                ).allowMainThreadQueries().build()
                val reposList = db.reposListDao().getById(repoData.preferenceId!!)
                if (BuildConfig.DEBUG) Timber.d("Setting preference $preferenceName because it is not the Androidacy repo or the Magisk Alt Repo")
                if (repoData.isForceHide || reposList == null) {
                    if (BuildConfig.DEBUG) Timber.d("Hiding preference $preferenceName because it is null or force hidden")
                    hideRepoData(preferenceName)
                    return
                } else {
                    if (BuildConfig.DEBUG) Timber.d(
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
                                IntentHelper.openUrl(
                                    FoxActivity.getFoxActivity(this),
                                    repoData.getWebsite()
                                )
                                true
                            }
                    } else {
                        findPreference<Preference>(preferenceName + "_website")!!.isVisible =
                            false
                    }
                    if (repoData.getSupport() != null) {
                        findPreference<Preference>(preferenceName + "_support")!!.onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                IntentHelper.openUrl(
                                    FoxActivity.getFoxActivity(this),
                                    repoData.getSupport()
                                )
                                true
                            }
                    } else {
                        findPreference<Preference>("${preferenceName}_support")!!.isVisible =
                            false
                    }
                    if (repoData.getSubmitModule() != null) {
                        findPreference<Preference>(preferenceName + "_submit")!!.onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                IntentHelper.openUrl(
                                    FoxActivity.getFoxActivity(this),
                                    repoData.getSubmitModule()
                                )
                                true
                            }
                    } else {
                        findPreference<Preference>(preferenceName + "_submit")!!.isVisible =
                            false
                    }
                    if (repoData.getDonate() != null) {
                        findPreference<Preference>(preferenceName + "_donate")!!.onPreferenceClickListener =
                            Preference.OnPreferenceClickListener {
                                IntentHelper.openUrl(
                                    FoxActivity.getFoxActivity(this),
                                    repoData.getDonate()
                                )
                                true
                            }
                    } else {
                        findPreference<Preference>(preferenceName + "_donate")!!.isVisible =
                            false
                    }
                }
            } else {
                if (BuildConfig.DEBUG) Timber.d("Hiding preference $preferenceName because it's data is null")
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
                    MainApplication.INSTANCE!!.repoModules.clear()
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
                        IntentHelper.openUrl(FoxActivity.getFoxActivity(this), homepage)
                        true
                    }
                (preference as LongClickablePreference).onPreferenceLongClickListener =
                    LongClickablePreference.OnPreferenceLongClickListener {
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
                preference.setIcon(ActionButtonType.supportIconForUrl(supportUrl))
                preference.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        IntentHelper.openUrl(FoxActivity.getFoxActivity(this), supportUrl)
                        true
                    }
                (preference as LongClickablePreference).onPreferenceLongClickListener =
                    LongClickablePreference.OnPreferenceLongClickListener {
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
                preference.setIcon(ActionButtonType.donateIconForUrl(donateUrl))
                preference.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        IntentHelper.openUrl(FoxActivity.getFoxActivity(this), donateUrl)
                        true
                    }
                (preference as LongClickablePreference).onPreferenceLongClickListener =
                    LongClickablePreference.OnPreferenceLongClickListener {
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
                        IntentHelper.openUrl(FoxActivity.getFoxActivity(this), submissionUrl)
                        true
                    }
                (preference as LongClickablePreference).onPreferenceLongClickListener =
                    LongClickablePreference.OnPreferenceLongClickListener {
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
        setRepoData(RepoManager.MAGISK_ALT_REPO)
        setRepoData(RepoManager.ANDROIDACY_MAGISK_REPO_ENDPOINT)
        updateCustomRepoList(true)
        onCreatePreferencesAndroidacy()
    }
}