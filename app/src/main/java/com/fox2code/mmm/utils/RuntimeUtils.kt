package com.fox2code.mmm.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.SetupActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import timber.log.Timber

@Suppress("UNUSED_PARAMETER")
class RuntimeUtils {
    @SuppressLint("RestrictedApi")
    private fun ensurePermissions(context: Context, activity: MainActivity) {
        if (BuildConfig.DEBUG) Timber.i("Ensure Permissions")
        // First, check if user has said don't ask again by checking if pref_dont_ask_again_notification_permission is true
        if (!PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("pref_dont_ask_again_notification_permission", false)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (BuildConfig.DEBUG) Timber.i("Request Notification Permission")
                if (FoxActivity.getFoxActivity(context)
                        .shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    // Show a dialog explaining why we need context permission, which is to show
                    // notifications for updates
                    activity.runOnUiThread {
                        if (BuildConfig.DEBUG) Timber.i("Show Notification Permission Dialog")
                        val builder = MaterialAlertDialogBuilder(context)
                        builder.setTitle(R.string.permission_notification_title)
                        builder.setMessage(R.string.permission_notification_message)
                        // Don't ask again checkbox
                        val view: View =
                            activity.layoutInflater.inflate(R.layout.dialog_checkbox, null)
                        val checkBox = view.findViewById<CheckBox>(R.id.checkbox)
                        checkBox.setText(R.string.dont_ask_again)
                        checkBox.setOnCheckedChangeListener { buttonView: CompoundButton?, isChecked: Boolean ->
                            PreferenceManager.getDefaultSharedPreferences(
                                context
                            ).edit().putBoolean(
                                "pref_dont_ask_again_notification_permission", isChecked
                            ).apply()
                        }
                        builder.setView(view)
                        builder.setPositiveButton(R.string.permission_notification_grant) { dialog, which ->
                            // Request the permission
                            activity.requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
                            )
                            MainActivity.doSetupNowRunning = false
                        }
                        builder.setNegativeButton(R.string.cancel) { dialog, which ->
                            // Set pref_background_update_check to false and dismiss dialog
                            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                            prefs.edit().putBoolean("pref_background_update_check", false).apply()
                            dialog.dismiss()
                            MainActivity.doSetupNowRunning = false
                        }
                        builder.show()
                        if (BuildConfig.DEBUG) Timber.i("Show Notification Permission Dialog Done")
                    }
                } else {
                    // Request the permission
                    if (BuildConfig.DEBUG) Timber.i("Request Notification Permission")
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                    if (BuildConfig.DEBUG) {
                        // Log if granted via onRequestPermissionsResult
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        Timber.i("Request Notification Permission Done. Result: %s", granted)
                    }
                    MainActivity.doSetupNowRunning = false
                }
                // Next branch is for < android 13 and user has blocked notifications
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(
                    context
                ).areNotificationsEnabled()
            ) {
                activity.runOnUiThread {
                    val builder = MaterialAlertDialogBuilder(context)
                    builder.setTitle(R.string.permission_notification_title)
                    builder.setMessage(R.string.permission_notification_message)
                    // Don't ask again checkbox
                    val view: View = activity.layoutInflater.inflate(R.layout.dialog_checkbox, null)
                    val checkBox = view.findViewById<CheckBox>(R.id.checkbox)
                    checkBox.setText(R.string.dont_ask_again)
                    checkBox.setOnCheckedChangeListener { buttonView: CompoundButton?, isChecked: Boolean ->
                        PreferenceManager.getDefaultSharedPreferences(
                            context
                        ).edit()
                            .putBoolean("pref_dont_ask_again_notification_permission", isChecked)
                            .apply()
                    }
                    builder.setView(view)
                    builder.setPositiveButton(R.string.permission_notification_grant) { dialog, which ->
                        // Open notification settings
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts("package", activity.packageName, null)
                        intent.data = uri
                        activity.startActivity(intent)
                        MainActivity.doSetupNowRunning = false
                    }
                    builder.setNegativeButton(R.string.cancel) { dialog, which ->
                        // Set pref_background_update_check to false and dismiss dialog
                        val prefs = MainApplication.getSharedPreferences("mmm")
                        prefs.edit().putBoolean("pref_background_update_check", false).apply()
                        dialog.dismiss()
                        MainActivity.doSetupNowRunning = false
                    }
                    builder.show()
                }
            } else {
                MainActivity.doSetupNowRunning = false
            }
        } else {
            if (BuildConfig.DEBUG) Timber.i("Notification Permission Already Granted or Don't Ask Again")
            MainActivity.doSetupNowRunning = false
        }
    }

    /**
     * Shows setup activity if it's the first launch
     */
    // Method to show a setup box on first launch
    @SuppressLint("InflateParams", "RestrictedApi", "UnspecifiedImmutableFlag", "ApplySharedPref")
    fun checkShowInitialSetup(context: Context, activity: MainActivity) {
        if (BuildConfig.DEBUG) Timber.i("Checking if we need to run setup")
        // Check if context is the first launch using prefs and if doSetupRestarting was passed in the intent
        val prefs = MainApplication.getSharedPreferences("mmm")
        var firstLaunch = prefs.getString("last_shown_setup", null) != "v2"
        // First launch
        // context is intentionally separate from the above if statement, because it needs to be checked even if the first launch check is true due to some weird edge cases
        if (activity.intent.getBooleanExtra("doSetupRestarting", false)) {
            // Restarting setup
            firstLaunch = false
        }
        if (BuildConfig.DEBUG) {
            Timber.i(
                "First launch: %s, pref value: %s",
                firstLaunch,
                prefs.getString("last_shown_setup", null)
            )
        }
        if (firstLaunch) {
            MainActivity.doSetupNowRunning = true
            // Launch setup wizard
            if (BuildConfig.DEBUG) Timber.i("Launching setup wizard")
            // Show setup activity
            val intent = Intent(context, SetupActivity::class.java)
            activity.finish()
            activity.startActivity(intent)
        } else {
            ensurePermissions(context, activity)
        }
    }

    /**
     * @return true if the load workflow must be stopped.
     */
    fun waitInitialSetupFinished(context: Context, activity: MainActivity): Boolean {
        if (BuildConfig.DEBUG) Timber.i("waitInitialSetupFinished")
        if (MainActivity.doSetupNowRunning) activity.updateScreenInsets() // Fix an edge case
        try {
            // Wait for doSetupNow to finish
            while (MainActivity.doSetupNowRunning) {
                Thread.sleep(50)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        }
        return MainActivity.doSetupRestarting
    }

    /**
     * Shows a snackbar offering to take users to Weblate if their language is not available.
     *
     * @param language     The language code.
     * @param languageName The language name.
     */
    @SuppressLint("RestrictedApi")
    fun showWeblateSnackbar(
        context: Context, activity: MainActivity, language: String, languageName: String
    ) {
        // if we haven't shown context snackbar for context version yet
        val prefs = MainApplication.getSharedPreferences("mmm")
        if (prefs.getInt("weblate_snackbar_shown", 0) == BuildConfig.VERSION_CODE) return
        val snackbar: Snackbar = Snackbar.make(
            activity.findViewById<View>(R.id.root_container),
            activity.getString(R.string.language_not_available, languageName),
            Snackbar.LENGTH_LONG
        )
        snackbar.setAction(R.string.ok) { v ->
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://translate.nift4.org/engage/foxmmm/?language=$language")
            activity.startActivity(intent)
        }
        snackbar.show()
        prefs.edit().putInt("weblate_snackbar_shown", BuildConfig.VERSION_CODE).apply()
    }
}