package com.fox2code.mmm.settings

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.installer.InstallerInitializer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.apache.commons.io.FileUtils
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Date

class DebugFragment : PreferenceFragmentCompat() {
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
        setPreferencesFromResource(R.xml.debugging_preferences, rootKey)


        if (!MainApplication.isDeveloper) {
            findPreference<Preference>("pref_disable_low_quality_module_filter")!!.isVisible = false
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
                            FileUtils.forceMkdir(File(MainApplication.getInstance().dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/wasm"))
                            FileUtils.forceMkdir(File(MainApplication.getInstance().dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/js"))
                            Toast.makeText(
                                requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Timber.e(e)
                            Toast.makeText(
                                requireContext(), R.string.cache_clear_failed, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int -> }.show()
                true
            }
        if (!BuildConfig.DEBUG || InstallerInitializer.peekMagiskPath() == null) {
            // Hide the pref_crash option if not in debug mode - stop users from purposely crashing the app
            if (MainApplication.forceDebugLogging) Timber.i(InstallerInitializer.peekMagiskPath())
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
                            ).setPositiveButton(R.string.yes) { _: DialogInterface?, _: Int ->
                                // Clear app data
                                MainApplication.getInstance().resetApp()
                            }.setNegativeButton(R.string.no) { _: DialogInterface?, _: Int -> }
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
        if (InstallerInitializer.peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND || !MainApplication.isDeveloper || InstallerInitializer.isKsu) {
            findPreference<Preference>("pref_use_magisk_install_command")!!.isVisible = false
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
                        requireContext(), R.string.logs_saved, Toast.LENGTH_SHORT
                    ).show()
                } catch (e: IOException) {
                    e.printStackTrace()
                    Toast.makeText(
                        requireContext(), R.string.error_saving_logs, Toast.LENGTH_SHORT
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
                // save logs to our external storage - name is current date and time
                try {
                    val extStorage = File(requireContext().getExternalFilesDir(null), "logs" + File.separator + "log-" + Date().toString() + ".txt")
                    FileUtils.copyFile(logsFile, extStorage)
                } catch (e: IOException) {
                    e.printStackTrace()
                    Toast.makeText(
                        requireContext(), R.string.error_saving_logs, Toast.LENGTH_SHORT
                    ).show()
                    return@setOnPreferenceClickListener true
                }
                // Share logs
                val shareIntent = Intent()
                // create a new intent and grantUriPermission to the file provider
                shareIntent.action = Intent.ACTION_SEND
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                shareIntent.putExtra(
                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                        requireContext(), BuildConfig.APPLICATION_ID + ".file-provider", logsFile
                    )
                )
                shareIntent.type = "text/plain"
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs)))
                true
            }
        // handle pref_force_debug_logging, which is always on in debug mode and off by default in release mode
        val forceDebugLogging = findPreference<SwitchPreferenceCompat>("pref_force_debug_logging")
        forceDebugLogging!!.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any? ->
                // set the debug logging flag
                MainApplication.forceDebugLogging = newValue as Boolean
                true
            }
        // force enable the pref_force_debug_logging if we're in debug mode and prevent users from disabling it
        if (BuildConfig.DEBUG) {
            forceDebugLogging.isEnabled = false
            forceDebugLogging.isChecked = true
        }
    }

}