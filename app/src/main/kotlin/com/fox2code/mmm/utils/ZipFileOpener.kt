/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.utils

import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskPath
import com.fox2code.mmm.utils.IntentHelper.Companion.openInstaller
import com.fox2code.mmm.utils.io.Files.Companion.getFileName
import com.fox2code.mmm.utils.io.Files.Companion.getFileSize
import com.fox2code.mmm.utils.io.PropUtils.Companion.readModulePropSimple
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ZipFileOpener : AppCompatActivity() {
    var loading: AlertDialog? = null

    // Adds us as a handler for zip files, so we can pass them to the installer
    // We should have a content uri provided to us.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loading = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.loading)
            .setMessage(R.string.zip_unpacking)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _: DialogInterface, _: Int ->
                finishAndRemoveTask()
            }
            .show()
        Thread(Runnable {
            if (MainApplication.forceDebugLogging) Timber.d("onCreate: %s", intent)
            val zipFile: File
            val uri = intent.data
            if (uri == null) {
                Timber.e("onCreate: No data provided")
                runOnUiThread {
                    Toast.makeText(this, R.string.zip_load_failed, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                }
                return@Runnable
            }
            // Try to copy the file to our cache
            try {
                // check if its a file over 10MB
                var fileSize = getFileSize(this, uri)
                if (fileSize == null) fileSize = 0L
                if (1000L * 1000 * 10 < fileSize) {
                    runOnUiThread { loading!!.show() }
                }
                zipFile = File.createTempFile("module", ".zip", cacheDir)
                contentResolver.openInputStream(uri).use { inputStream ->
                    FileOutputStream(zipFile).use { outputStream ->
                        if (inputStream == null) {
                            Timber.e("onCreate: Failed to open input stream")
                            runOnUiThread {
                                Toast.makeText(this, R.string.zip_load_failed, Toast.LENGTH_LONG)
                                    .show()
                                finishAndRemoveTask()
                            }
                            return@Runnable
                        }
                        val buffer = ByteArray(4096)
                        var read: Int
                        try {
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                outputStream.write(buffer, 0, read)
                            }
                        } catch (e: IOException) {
                            Timber.e(e, "onCreate: Failed to copy zip file")
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    R.string.zip_load_failed,
                                    Toast.LENGTH_LONG
                                ).show()
                                finishAndRemoveTask()
                            }
                            return@Runnable
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "onCreate: Failed to copy zip file")
                runOnUiThread {
                    Toast.makeText(this, R.string.zip_load_failed, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                }
                return@Runnable
            }
            // Ensure zip is not empty
            if (zipFile.length() == 0L) {
                Timber.e("onCreate: Zip file is empty")
                runOnUiThread {
                    Toast.makeText(this, R.string.zip_load_failed, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                }
                return@Runnable
            } else {
                if (MainApplication.forceDebugLogging) Timber.d("onCreate: Zip file is " + zipFile.length() + " bytes")
            }
            var entry: ZipEntry?
            var zip: ZipFile? = null
            // Unpack the zip to validate it's a valid magisk module
            // It needs to have, at the bare minimum, a module.prop file. Everything else is technically optional.
            // First, check if it's a zip file
            try {
                zip = ZipFile(zipFile)
                if (zip.getEntry("module.prop").also { entry = it } == null) {
                    Timber.e("onCreate: Zip file is not a valid magisk module")
                    if (MainApplication.forceDebugLogging) {
                        Timber.d(
                            "onCreate: Zip file contents: %s",
                            zip.stream().map { obj: ZipEntry -> obj.name }
                                .reduce { a: String, b: String -> "$a, $b" }.orElse("empty")
                        )
                    }
                    runOnUiThread {
                        Toast.makeText(this, R.string.invalid_format, Toast.LENGTH_LONG).show()
                        finishAndRemoveTask()
                    }
                    return@Runnable
                }
            } catch (e: Exception) {
                Timber.e(e, "onCreate: Failed to open zip file")
                runOnUiThread {
                    Toast.makeText(this, R.string.zip_load_failed, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                }
                if (zip != null) {
                    try {
                        zip.close()
                    } catch (exception: IOException) {
                        Timber.e(Log.getStackTraceString(exception))
                    }
                }
                return@Runnable
            }
            if (MainApplication.forceDebugLogging) Timber.d("onCreate: Zip file is valid")
            var moduleInfo: String?
            try {
                moduleInfo = readModulePropSimple(zip.getInputStream(entry), "name")
                if (moduleInfo == null) {
                    moduleInfo = readModulePropSimple(zip.getInputStream(entry), "id")
                }
                if (moduleInfo == null) {
                    throw NullPointerException("moduleInfo is null, check earlier logs for root cause")
                }
            } catch (e: Exception) {
                Timber.e(e, "onCreate: Failed to load module id")
                runOnUiThread {
                    Toast.makeText(this, R.string.zip_prop_load_failed, Toast.LENGTH_LONG).show()
                    finishAndRemoveTask()
                }
                try {
                    zip.close()
                } catch (exception: IOException) {
                    Timber.e(Log.getStackTraceString(exception))
                }
                return@Runnable
            }
            try {
                zip.close()
            } catch (exception: IOException) {
                Timber.e(Log.getStackTraceString(exception))
            }
            val finalModuleInfo: String = moduleInfo
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.zip_security_warning, finalModuleInfo))
                    .setMessage(
                        getString(
                            R.string.zip_intent_module_install,
                            finalModuleInfo,
                            getFileName(this, uri)
                        )
                    )
                    .setCancelable(false)
                    .setNegativeButton(R.string.no) { d: DialogInterface, _: Int ->
                        d.dismiss()
                        finishAndRemoveTask()
                    }
                    .setPositiveButton(R.string.yes) { d: DialogInterface, _: Int ->
                        d.dismiss()
                        // Pass the file to the installer
                        val compatActivity = this
                        openInstaller(
                            compatActivity, zipFile.absolutePath,
                            compatActivity.getString(
                                R.string.local_install_title
                            ), null, null, false,
                            BuildConfig.DEBUG &&  // Use debug mode if no root
                                    peekMagiskPath() == null
                        )
                        finish()
                    }
                    .show()
                loading!!.dismiss()
            }
        }).start()
    }
}