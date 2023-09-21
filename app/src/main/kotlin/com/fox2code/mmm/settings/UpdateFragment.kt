package com.fox2code.mmm.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.AppUpdateManager
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.UpdateActivity
import com.fox2code.mmm.background.BackgroundUpdateChecker
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import timber.log.Timber
import java.util.Random

class UpdateFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

        val name = "mmmx"
        val context: Context? = MainApplication.INSTANCE
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
        setPreferencesFromResource(R.xml.update_preferences, rootKey)
        // track all non empty values
        val sharedPreferences = dataStore.sharedPreferences
        val debugNotification = findPreference<Preference>("pref_background_update_check_debug")
        val updateCheckExcludes =
            findPreference<Preference>("pref_background_update_check_excludes")
        val updateCheckVersionExcludes =
            findPreference<Preference>("pref_background_update_check_excludes_version")
        debugNotification!!.isEnabled = MainApplication.isBackgroundUpdateCheckEnabled
        debugNotification.isVisible =
            MainApplication.isDeveloper && !MainApplication.isWrapped && MainApplication.isBackgroundUpdateCheckEnabled
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
                    if (MainApplication.forceDebugLogging) Timber.d("Fake version: %s, count: %s", fakeVersion, i)
                    updateableModules["FakeModule $i"] = "1.0.$fakeVersion"
                }
                BackgroundUpdateChecker.postNotification(
                    requireContext(), updateableModules, count, true
                )
                true
            }
        val backgroundUpdateCheck = findPreference<Preference>("pref_background_update_check")
        backgroundUpdateCheck!!.isVisible = !MainApplication.isWrapped
        // Make uncheckable if POST_NOTIFICATIONS permission is not granted
        if (!MainApplication.isNotificationPermissionGranted) {
            // Instead of disabling the preference, we make it uncheckable and when the user
            // clicks on it, we show a dialog explaining why the permission is needed
            backgroundUpdateCheck.onPreferenceClickListener =
                Preference.OnPreferenceClickListener { _: Preference? ->
                    // set the box to unchecked
                    (backgroundUpdateCheck as SwitchPreferenceCompat?)!!.isChecked = false
                    // ensure that the preference is false
                    MainApplication.getPreferences("mmm")!!.edit()
                        .putBoolean("pref_background_update_check", false).apply()
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.permission_notification_title)
                        .setMessage(
                            R.string.permission_notification_message
                        ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                            // Open the app settings
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts("package", requireContext().packageName, null)
                            intent.data = uri
                            this.startActivity(intent)
                        }.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
                        .show()
                    true
                }
            backgroundUpdateCheck.setSummary(R.string.background_update_check_permission_required)
        }
        updateCheckExcludes!!.isVisible =
            MainApplication.isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
        backgroundUpdateCheck.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
                val enabled = java.lang.Boolean.parseBoolean(newValue.toString())
                debugNotification.isEnabled = enabled
                debugNotification.isVisible =
                    MainApplication.isDeveloper && !MainApplication.isWrapped && enabled
                updateCheckExcludes.isEnabled = enabled
                updateCheckExcludes.isVisible = enabled && !MainApplication.isWrapped
                if (!enabled) {
                    BackgroundUpdateChecker.onMainActivityResume(requireContext())
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
                        "pref_background_update_check_excludes", HashSet()
                    )
                    // copy to a new set so we can modify it
                    val stringSet: MutableSet<String> = HashSet(stringSetTemp!!)
                    for ((i, localModuleInfo) in localModuleInfos.withIndex()) {
                        moduleNames[i] = localModuleInfo!!.name
                        // Stringset uses id, we show name
                        checkedItems[i] = stringSet.contains(localModuleInfo.id)
                        if (MainApplication.forceDebugLogging) Timber.d("name: %s, checked: %s", moduleNames[i], checkedItems[i])
                    }
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                        .setMultiChoiceItems(
                            moduleNames, checkedItems
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
                                "pref_background_update_check_excludes", stringSet
                            ).apply()
                        }.setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }.show()
                } else {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                        .setMessage(
                            R.string.background_update_check_excludes_no_modules
                        ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }.show()
                }
                true
            }
        // now handle pref_background_update_check_excludes_version
        updateCheckVersionExcludes!!.isVisible =
            MainApplication.isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
        updateCheckVersionExcludes.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                // get the stringset pref_background_update_check_excludes_version
                val stringSet = sharedPreferences.getStringSet(
                    "pref_background_update_check_excludes_version", HashSet()
                )
                if (MainApplication.forceDebugLogging) Timber.d("stringSet: %s", stringSet)
                // for every module, add it's name and a text field to the dialog. the text field should accept a comma separated list of versions
                val localModuleInfos: Collection<LocalModuleInfo?> =
                    ModuleManager.instance!!.modules.values
                // make sure we have modules
                if (localModuleInfos.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.background_update_check_excludes)
                        .setMessage(
                            R.string.background_update_check_excludes_no_modules
                        ).setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int -> }.show()
                } else {
                    val layout = LinearLayout(requireContext())
                    layout.orientation = LinearLayout.VERTICAL
                    val params = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
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
                        val id =
                            localModuleInfos.stream().filter { localModuleInfo1: LocalModuleInfo? ->
                                localModuleInfo1!!.name.equals(
                                    localModuleInfo.name
                                )
                            }.findFirst().orElse(null)!!.id
                        val version = stringSet!!.stream().filter { s: String -> s.startsWith(id) }
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
                            if (MainApplication.forceDebugLogging) Timber.d("ok clicked")
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
                                            }.findFirst().orElse(null)!!.id + ":" + text
                                    )
                                    if (MainApplication.forceDebugLogging) Timber.d("text is %s for %s", text, editText.hint.toString())
                                } else {
                                    if (MainApplication.forceDebugLogging) Timber.d("text is empty for %s", editText.hint.toString())
                                }
                            }
                            sharedPreferences.edit().putStringSet(
                                "pref_background_update_check_excludes_version", stringSetTemp
                            ).apply()
                        }.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
                        .show()
                }
                true
            }

        val clipboard =
            requireContext().getSystemService(AppCompatActivity.CLIPBOARD_SERVICE) as ClipboardManager
        val linkClickable = findPreference<LongClickablePreference>("pref_update")
        linkClickable!!.isVisible =
            BuildConfig.ENABLE_AUTO_UPDATER && (BuildConfig.DEBUG || AppUpdateManager.appUpdateManager.peekHasUpdate())
        linkClickable.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { _: Preference? ->
                // open UpdateActivity with CHECK action
                val intent = Intent(requireContext(), UpdateActivity::class.java)
                intent.action = UpdateActivity.ACTIONS.CHECK.name
                startActivity(intent)
                true
            }
        linkClickable.onPreferenceLongClickListener =
            LongClickablePreference.OnPreferenceLongClickListener { _: Preference? ->
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
            MainApplication.isDeveloper && MainApplication.isBackgroundUpdateCheckEnabled && !MainApplication.isWrapped
        debugDownload.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { _: Preference? ->
                val intent = Intent(requireContext(), UpdateActivity::class.java)
                intent.action = UpdateActivity.ACTIONS.DOWNLOAD.name
                startActivity(intent)
                true
            }
    }
}
