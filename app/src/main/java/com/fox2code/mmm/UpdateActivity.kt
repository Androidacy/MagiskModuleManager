package com.fox2code.mmm

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.FileProvider
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.utils.io.net.Http
import com.google.android.material.bottomnavigation.BottomNavigationItemView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import io.noties.markwon.Markwon
import org.json.JSONException
import org.json.JSONObject
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Objects

class UpdateActivity : FoxActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MainApplication.getINSTANCE().isMatomoAllowed) {
            TrackHelper.track().screen(this).with(MainApplication.getINSTANCE().tracker)
        }
        setContentView(R.layout.activity_update)
        // Get the progress bar and make it indeterminate for now
        val progressIndicator = findViewById<LinearProgressIndicator>(R.id.update_progress)
        progressIndicator.isIndeterminate = true
        // get update_cancel item on bottom navigation
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val updateCancel =
            bottomNavigationView.findViewById<BottomNavigationItemView>(R.id.update_cancel_button)
        // get status text view
        val statusTextView = findViewById<MaterialTextView>(R.id.update_progress_text)
        // set status text to please wait
        statusTextView.setText(R.string.please_wait)
        val updateThread: Thread = object : Thread() {
            override fun run() {
                // Now, parse the intent
                val extras = intent.action
                // if extras is null, then we are in a bad state or user launched the activity manually
                if (extras == null) {
                    runOnUiThread {

                        // set status text to error
                        statusTextView.setText(R.string.error_no_extras)
                        // set progress bar to error
                        progressIndicator.isIndeterminate = false
                        progressIndicator.setProgressCompat(0, false)
                    }
                    return
                }

                // get action
                val action = ACTIONS.valueOf(extras)
                // if action is null, then we are in a bad state or user launched the activity manually
                if (Objects.isNull(action)) {
                    runOnUiThread {

                        // set status text to error
                        statusTextView.setText(R.string.error_no_action)
                        // set progress bar to error
                        progressIndicator.isIndeterminate = false
                        progressIndicator.setProgressCompat(0, false)
                    }
                    // return
                    return
                }

                // For check action, we need to check if there is an update using the AppUpdateManager.peekShouldUpdate()
                when (action) {
                    ACTIONS.CHECK -> {
                        checkForUpdate()
                    }
                    ACTIONS.DOWNLOAD -> {
                        try {
                            downloadUpdate()
                        } catch (e: JSONException) {
                            runOnUiThread {

                                // set status text to error
                                statusTextView.setText(R.string.error_download_update)
                                // set progress bar to error
                                progressIndicator.isIndeterminate = false
                                progressIndicator.setProgressCompat(100, false)
                            }
                        }
                    }
                    ACTIONS.INSTALL -> {
                        // ensure path was passed and points to a file within our cache directory. replace .. and url encoded characters
                        val path =
                            intent.getStringExtra("path")?.trim { it <= ' ' }
                                ?.replace("\\.\\.".toRegex(), "")?.replace("%2e%2e".toRegex(), "")
                        if (path!!.isEmpty()) {
                            runOnUiThread {
                                // set status text to error
                                statusTextView.setText(R.string.no_file_found)
                                // set progress bar to error
                                progressIndicator.isIndeterminate = false
                                progressIndicator.setProgressCompat(0, false)
                            }
                            return
                        }
                        // check and sanitize file path
                        // path must be in our cache directory
                        if (!path.startsWith(cacheDir.absolutePath)) {
                            throw SecurityException("Path is not in cache directory: $path")
                        }
                        val file = File(path)
                        val parentFile = file.parentFile
                        try {
                            if (parentFile == null || !parentFile.canonicalPath.startsWith(cacheDir.canonicalPath)) {
                                throw SecurityException("Path is not in cache directory: $path")
                            }
                        } catch (e: IOException) {
                            throw SecurityException("Path is not in cache directory: $path")
                        }
                        if (!file.exists()) {
                            runOnUiThread {

                                // set status text to error
                                statusTextView.setText(R.string.no_file_found)
                                // set progress bar to error
                                progressIndicator.isIndeterminate = false
                                progressIndicator.setProgressCompat(0, false)
                            }
                            // return
                            return
                        }
                        if (file.parentFile != cacheDir) {
                            // set status text to error
                            runOnUiThread {
                                statusTextView.setText(R.string.no_file_found)
                                // set progress bar to error
                                progressIndicator.isIndeterminate = false
                                progressIndicator.setProgressCompat(0, false)
                            }
                            // return
                            return
                        }
                        // set status text to installing
                        statusTextView.setText(R.string.installing_update)
                        // set progress bar to indeterminate
                        progressIndicator.isIndeterminate = true
                        // install update
                        installUpdate(file)
                    }
                }
            }
        }
        // on click, finish the activity and anything running in it
        updateCancel.setOnClickListener { _: View? ->
            // end any download
            updateThread.interrupt()
            forceBackPressed()
            finish()
        }
        updateThread.start()
    }

    @SuppressLint("RestrictedApi")
    fun checkForUpdate() {
        // get status text view
        val statusTextView = findViewById<MaterialTextView>(R.id.update_progress_text)
        val progressIndicator = findViewById<LinearProgressIndicator>(R.id.update_progress)
        runOnUiThread {
            progressIndicator.isIndeterminate = true
            // set status text to checking for update
            statusTextView.setText(R.string.checking_for_update)
            // set progress bar to indeterminate
            progressIndicator.isIndeterminate = true
        }
        // check for update
        val shouldUpdate = AppUpdateManager.appUpdateManager.peekShouldUpdate()
        // if shouldUpdate is true, then we have an update
        if (shouldUpdate) {
            runOnUiThread {

                // set status text to update available
                statusTextView.setText(R.string.update_available)
                // set button text to download
                val button = findViewById<BottomNavigationItemView>(R.id.action_update)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    button.tooltipText = getString(R.string.download_update)
                }
                button.isEnabled = true
            }
            // return
        } else {
            runOnUiThread {
                // set status text to no update available
                statusTextView.setText(R.string.no_update_available)
            }
            // set progress bar to error
            // return
        }
        runOnUiThread {
            progressIndicator.isIndeterminate = false
            progressIndicator.setProgressCompat(100, false)
        }
        return
    }

    @Throws(JSONException::class)
    fun downloadUpdate() {
        val progressIndicator = findViewById<LinearProgressIndicator>(R.id.update_progress)
        runOnUiThread { progressIndicator.isIndeterminate = true }
        // get status text view
        val statusTextView = findViewById<MaterialTextView>(R.id.update_progress_text)
        var lastestJSON: ByteArray? = ByteArray(0)
        try {
            lastestJSON = Http.doHttpGet(AppUpdateManager.RELEASES_API_URL, false)
        } catch (e: Exception) {
            // when logging, REMOVE the json from the log
            Timber.e(e, "Error downloading update info")
            runOnUiThread {
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
                statusTextView.setText(R.string.error_download_update)
            }
        }
        // convert to JSON
        val latestJSON = JSONObject(String(lastestJSON!!))
        val changelog = latestJSON.getString("body")
        // set changelog text. changelog could be markdown, so we need to convert it to HTML
        val changelogTextView = findViewById<MaterialTextView>(R.id.update_changelog)
        val markwon = Markwon.builder(this).build()
        runOnUiThread { markwon.setMarkdown(changelogTextView, changelog) }
        // we already know that there is an update, so we can get the latest version of our architecture. We're going to have to iterate through the assets to find the one we want
        val assets = latestJSON.getJSONArray("assets")
        // get the asset we want
        var asset: JSONObject? = null
        // iterate through assets until we find the one that contains Build.SUPPORTED_ABIS[0]
        while (Objects.isNull(asset)) {
            for (i in 0 until assets.length()) {
                val asset1 = assets.getJSONObject(i)
                if (asset1.getString("name").contains(Build.SUPPORTED_ABIS[0])) {
                    asset = asset1
                    break
                }
            }
        }
        // if asset is null, then we are in a bad state
        if (Objects.isNull(asset)) {
            // set status text to error
            runOnUiThread {
                statusTextView.setText(R.string.error_no_asset)
                // set progress bar to error
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
            }
            // return
            return
        }
        // get the download url
        val downloadUrl = asset?.getString("browser_download_url")
        // get the download size
        val downloadSize = asset?.getLong("size")
        runOnUiThread {

            // set status text to downloading update
            statusTextView.text = getString(R.string.downloading_update, 0)
            // set progress bar to 0
            progressIndicator.isIndeterminate = false
            progressIndicator.setProgressCompat(0, false)
        }
        // download the update
        var update = ByteArray(0)
        try {
            update = Http.doHttpGet(downloadUrl!!) { downloaded: Int, total: Int, _: Boolean ->
                runOnUiThread {

                    // update progress bar
                    progressIndicator.setProgressCompat(
                        (downloaded.toFloat() / total.toFloat() * 100).toInt(),
                        true
                    )
                    // update status text
                    statusTextView.text = getString(
                        R.string.downloading_update,
                        (downloaded.toFloat() / total.toFloat() * 100).toInt()
                    )
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
                statusTextView.setText(R.string.error_download_update)
            }
        }
        // if update is null, then we are in a bad state
        if (Objects.isNull(update)) {
            runOnUiThread {

                // set status text to error
                statusTextView.setText(R.string.error_download_update)
                // set progress bar to error
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
            }
            // return
            return
        }
        // if update is not the same size as the download size, then we are in a bad state
        if (update.size.toLong() != downloadSize) {
            runOnUiThread {

                // set status text to error
                statusTextView.setText(R.string.error_download_update)
                // set progress bar to error
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
            }
            // return
            return
        }
        // set status text to installing update
        runOnUiThread {
            statusTextView.setText(R.string.installing_update)
            // set progress bar to 100
            progressIndicator.isIndeterminate = true
            progressIndicator.setProgressCompat(100, false)
        }
        // save the update to the cache
        var updateFile: File? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            updateFile = File(cacheDir, "update.apk")
            fileOutputStream = FileOutputStream(updateFile)
            fileOutputStream.write(update)
        } catch (e: IOException) {
            runOnUiThread {
                progressIndicator.isIndeterminate = false
                progressIndicator.setProgressCompat(100, false)
                statusTextView.setText(R.string.error_download_update)
            }
        } finally {
            if (Objects.nonNull(updateFile)) {
                updateFile?.deleteOnExit()
            }
            try {
                fileOutputStream?.close()
            } catch (ignored: IOException) {
            }
        }
        // install the update
        installUpdate(updateFile)
        // return
        return
    }

    @SuppressLint("RestrictedApi")
    private fun installUpdate(updateFile: File?) {
        // get status text view
        runOnUiThread {
            val statusTextView = findViewById<MaterialTextView>(R.id.update_progress_text)
            // set status text to installing update
            statusTextView.setText(R.string.installing_update)
            // set progress bar to 100
            val progressIndicator = findViewById<LinearProgressIndicator>(R.id.update_progress)
            progressIndicator.isIndeterminate = true
            progressIndicator.setProgressCompat(100, false)
        }
        // request install permissions
        val intent = Intent(Intent.ACTION_VIEW)
        val context = applicationContext
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.file-provider",
            updateFile!!
        )
        intent.setDataAndTypeAndNormalize(uri, "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        // return
        return
    }

    enum class ACTIONS {
        // action can be CHECK, DOWNLOAD, INSTALL
        CHECK, DOWNLOAD, INSTALL
    }
}