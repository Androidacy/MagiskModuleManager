/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

@file:Suppress("ktConcatNullable")

package com.fox2code.mmm.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.app.BundleCompat
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainActivity
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks.Companion.getConfigIntent
import com.fox2code.mmm.XHooks.Companion.isModuleActive
import com.fox2code.mmm.androidacy.AndroidacyActivity
import com.fox2code.mmm.installer.InstallerActivity
import com.fox2code.mmm.markdown.MarkdownActivity
import com.fox2code.mmm.utils.io.Files.Companion.closeSilently
import com.fox2code.mmm.utils.io.Files.Companion.copy
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.io.SuFileInputStream
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URISyntaxException

enum class IntentHelper {;

    companion object {

        interface OnFileReceivedCallback {
            fun onReceived(target: File?, uri: Uri?, response: Int)
        }

        private const val EXTRA_TAB_SESSION = "android.support.customtabs.extra.SESSION"
        private const val EXTRA_TAB_COLOR_SCHEME = "androidx.browser.customtabs.extra.COLOR_SCHEME"
        private const val EXTRA_TAB_COLOR_SCHEME_DARK = 2
        private const val EXTRA_TAB_COLOR_SCHEME_LIGHT = 1
        private const val EXTRA_TAB_TOOLBAR_COLOR = "android.support.customtabs.extra.TOOLBAR_COLOR"
        private const val EXTRA_TAB_EXIT_ANIMATION_BUNDLE =
            "android.support.customtabs.extra.EXIT_ANIMATION_BUNDLE"
        const val FLAG_GRANT_URI_PERMISSION = Intent.FLAG_GRANT_READ_URI_PERMISSION

        @JvmStatic
        fun openUri(context: Context, uri: String) {
            if (uri.startsWith("intent://")) {
                try {
                    startActivity(context, Intent.parseUri(uri, Intent.URI_INTENT_SCHEME), false)
                } catch (e: URISyntaxException) {
                    Timber.e(e)
                } catch (e: ActivityNotFoundException) {
                    Timber.e(e)
                }
            } else openUrl(context, uri)
        }

        @JvmOverloads
        fun openUrl(context: Context, url: String?, forceBrowser: Boolean = false) {
            Timber.d("Opening url: %s, forced browser %b", url, forceBrowser)
            try {
                val myIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                myIntent.flags = FLAG_GRANT_URI_PERMISSION
                if (forceBrowser) {
                    myIntent.addCategory(Intent.CATEGORY_BROWSABLE)
                }
                startActivity(context, myIntent, false)
            } catch (e: ActivityNotFoundException) {
                Timber.d(e, "Could not find suitable activity to handle url")
                Toast.makeText(
                    context, FoxActivity.getFoxActivity(context).getString(
                        R.string.no_browser
                    ), Toast.LENGTH_LONG
                ).show()
            }
        }

        @JvmStatic
        fun openCustomTab(context: Context, url: String?) {
            Timber.d("Opening url: %s in custom tab", url)
            try {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                viewIntent.flags = FLAG_GRANT_URI_PERMISSION
                val tabIntent = Intent(viewIntent)
                tabIntent.flags = FLAG_GRANT_URI_PERMISSION
                tabIntent.addCategory(Intent.CATEGORY_BROWSABLE)
                startActivityEx(context, tabIntent, viewIntent)
            } catch (e: ActivityNotFoundException) {
                Timber.d(e, "Could not find suitable activity to handle url")
                Toast.makeText(
                    context, FoxActivity.getFoxActivity(context).getString(
                        R.string.no_browser
                    ), Toast.LENGTH_LONG
                ).show()
            }
        }

        @JvmStatic
        @JvmOverloads
        fun openUrlAndroidacy(
            context: Context,
            url: String?,
            allowInstall: Boolean,
            title: String? = null,
            config: String? = null
        ) {
            if (!hasWebView()) {
                Timber.w("Using custom tab for: %s", url)
                openCustomTab(context, url)
                return
            }
            val uri = Uri.parse(url)
            try {
                val myIntent = Intent(
                    Constants.INTENT_ANDROIDACY_INTERNAL,
                    uri,
                    context,
                    AndroidacyActivity::class.java
                )
                myIntent.putExtra(Constants.EXTRA_ANDROIDACY_ALLOW_INSTALL, allowInstall)
                if (title != null) myIntent.putExtra(
                    Constants.EXTRA_ANDROIDACY_ACTIONBAR_TITLE, title
                )
                if (config != null) myIntent.putExtra(
                    Constants.EXTRA_ANDROIDACY_ACTIONBAR_CONFIG, config
                )
                MainApplication.addSecret(myIntent)
                startActivity(context, myIntent, true)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    "No application can handle this request." + " Please install a web-browser",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        @Suppress("NAME_SHADOWING")
        @JvmStatic
        fun getPackageOfConfig(config: String): String {
            var config = config
            var i = config.indexOf(' ')
            if (i != -1) config = config.substring(0, i)
            i = config.indexOf('/')
            if (i != -1) config = config.substring(0, i)
            return config
        }

        @JvmStatic
        fun openConfig(context: Context, config: String) {
            val pkg = getPackageOfConfig(config)
            try {
                var intent = getConfigIntent(context, pkg, config)
                if (intent == null) {
                    if ("org.lsposed.manager" == config && (isModuleActive("riru_lsposed") || isModuleActive(
                            "zygisk_lsposed"
                        ))
                    ) {
                        Shell.getShell().newJob().add(
                            "am start -a android.intent.action.MAIN " + "-c org.lsposed.manager.LAUNCH_MANAGER " + "com.android.shell/.BugreportWarningActivity"
                        ).to(object : CallbackList<String?>() {
                            override fun onAddElement(str: String?) {
                                Timber.i("LSPosed: %s", str)
                            }
                        }).submit()
                        return
                    }
                    intent = Intent("android.intent.action.APPLICATION_PREFERENCES")
                    intent.setPackage(pkg)
                }
                intent.putExtra(Constants.EXTRA_FROM_MANAGER, true)
                startActivity(context, intent, false)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context, "Failed to launch module config activity", Toast.LENGTH_SHORT
                ).show()
            }
        }

        @JvmStatic
        fun openMarkdown(
            context: Context,
            url: String?,
            title: String?,
            config: String?,
            changeBoot: Boolean?,
            needsRamdisk: Boolean?,
            minMagisk: Int,
            minApi: Int,
            maxApi: Int
        ) {
            try {
                val intent = Intent(context, MarkdownActivity::class.java)
                MainApplication.addSecret(intent)
                intent.putExtra(Constants.EXTRA_MARKDOWN_URL, url)
                intent.putExtra(Constants.EXTRA_MARKDOWN_TITLE, title)
                intent.putExtra(Constants.EXTRA_MARKDOWN_CHANGE_BOOT, changeBoot)
                intent.putExtra(Constants.EXTRA_MARKDOWN_NEEDS_RAMDISK, needsRamdisk)
                intent.putExtra(Constants.EXTRA_MARKDOWN_MIN_MAGISK, minMagisk)
                intent.putExtra(Constants.EXTRA_MARKDOWN_MIN_API, minApi)
                intent.putExtra(Constants.EXTRA_MARKDOWN_MAX_API, maxApi)
                if (!config.isNullOrEmpty()) intent.putExtra(
                    Constants.EXTRA_MARKDOWN_CONFIG, config
                )
                startActivity(context, intent, true)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context, "Failed to launch markdown activity", Toast.LENGTH_SHORT
                ).show()
            }
        }

        @JvmStatic
        @JvmOverloads
        fun openInstaller(
            context: Context,
            url: String?,
            title: String?,
            config: String?,
            checksum: String?,
            mmtReborn: Boolean,
            testDebug: Boolean = false
        ) {
            try {
                val intent = Intent(context, InstallerActivity::class.java)
                intent.action = Constants.INTENT_INSTALL_INTERNAL
                MainApplication.addSecret(intent)
                intent.putExtra(Constants.EXTRA_INSTALL_PATH, url)
                intent.putExtra(Constants.EXTRA_INSTALL_NAME, title)
                if (!config.isNullOrEmpty()) intent.putExtra(
                    Constants.EXTRA_INSTALL_CONFIG, config
                )
                if (!checksum.isNullOrEmpty()) intent.putExtra(
                    Constants.EXTRA_INSTALL_CHECKSUM, checksum
                )
                if (mmtReborn) // Allow early styling of install process
                    intent.putExtra(Constants.EXTRA_INSTALL_MMT_REBORN, true)
                if (testDebug && BuildConfig.DEBUG) intent.putExtra(
                    Constants.EXTRA_INSTALL_TEST_ROOTLESS, true
                )
                startActivity(context, intent, true)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context, "Failed to launch markdown activity", Toast.LENGTH_SHORT
                ).show()
            }
        }

        fun startActivity(context: Context, activityClass: Class<out Activity?>?) {
            startActivity(context, Intent(context, activityClass), true)
        }

        @Throws(ActivityNotFoundException::class)
        fun startActivity(context: Context, intent: Intent?, sameApp: Boolean) {
            if (sameApp) {
                startActivityEx(context, intent, null)
            } else {
                startActivityEx(context, null, intent)
            }
        }

        @Throws(ActivityNotFoundException::class)
        fun startActivityEx(context: Context, intent1: Intent?, intent2: Intent?) {
            if (intent1 == null && intent2 == null) throw NullPointerException("No intent defined for activity!")
            changeFlags(intent1, true)
            changeFlags(intent2, false)
            val activity = getActivity(context)
            val param = ActivityOptionsCompat.makeCustomAnimation(
                context, android.R.anim.fade_in, android.R.anim.fade_out
            ).toBundle()
            if (activity == null) {
                if (intent1 != null) {
                    try {
                        context.startActivity(intent1, param)
                        return
                    } catch (e: ActivityNotFoundException) {
                        if (intent2 == null) throw e
                    }
                }
                context.startActivity(intent2, param)
            } else {
                if (intent1 != null) {
                    // Support Custom Tabs as sameApp intent
                    if (intent1.hasCategory(Intent.CATEGORY_BROWSABLE)) {
                        if (!intent1.hasExtra(EXTRA_TAB_SESSION)) {
                            val bundle = Bundle()
                            BundleCompat.putBinder(bundle, EXTRA_TAB_SESSION, null)
                            intent1.putExtras(bundle)
                        }
                        intent1.putExtra(EXTRA_TAB_EXIT_ANIMATION_BUNDLE, param)
                        if (activity is FoxActivity) {
                            val typedValue = TypedValue()
                            activity.getTheme().resolveAttribute(
                                android.R.attr.background, typedValue, true
                            )
                            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                                intent1.putExtra(EXTRA_TAB_TOOLBAR_COLOR, typedValue.data)
                                intent1.putExtra(
                                    EXTRA_TAB_COLOR_SCHEME,
                                    if (activity.isLightTheme) EXTRA_TAB_COLOR_SCHEME_LIGHT else EXTRA_TAB_COLOR_SCHEME_DARK
                                )
                            }
                        }
                    }
                    try {
                        intent1.putExtra(Constants.EXTRA_FADE_OUT, true)
                        activity.overridePendingTransition(
                            android.R.anim.fade_in, android.R.anim.fade_out
                        )
                        activity.startActivity(intent1, param)
                        return
                    } catch (e: ActivityNotFoundException) {
                        if (intent2 == null) throw e
                    }
                }
                activity.startActivity(intent2, param)
            }
        }

        private fun changeFlags(intent: Intent?, sameApp: Boolean) {
            if (intent == null) return
            var flags =
                intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT).inv()
            if (!sameApp) {
                flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                flags = if (intent.data == null) {
                    flags or Intent.FLAG_ACTIVITY_NEW_TASK
                } else {
                    flags or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                }
            }
            intent.flags = flags
        }

        @Suppress("NAME_SHADOWING")
        fun getActivity(context: Context?): Activity? {
            var context = context
            while (context !is Activity) {
                context = if (context is ContextWrapper) {
                    context.baseContext
                } else return null
            }
            return context
        }

        private const val RESPONSE_ERROR = 0
        const val RESPONSE_FILE = 1
        const val RESPONSE_URL = 2

        @SuppressLint("SdCardPath")
        fun openFileTo(
            compatActivity: FoxActivity, destination: File?, callback: OnFileReceivedCallback
        ) {
            var destinationFolder: File? = null
            if ((destination == null) || (destination.parentFile.also {
                    destinationFolder = it
                } == null) || (!destinationFolder?.mkdirs()!! && !destinationFolder!!.isDirectory)) {
                Timber.w("dest null.for open")
                callback.onReceived(destination, null, RESPONSE_ERROR)
                return
            }
            val intent = Intent(Intent.ACTION_GET_CONTENT).setType("application/zip")
            intent.flags = intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, false)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            val param = ActivityOptionsCompat.makeCustomAnimation(
                compatActivity, android.R.anim.fade_in, android.R.anim.fade_out
            ).toBundle()
            compatActivity.startActivityForResult(intent, param) { result: Int, data: Intent? ->
                val uri = data?.data
                if (uri == null || result == Activity.RESULT_CANCELED) {
                    Timber.d("invalid uri recieved")
                    callback.onReceived(destination, null, RESPONSE_ERROR)
                    return@startActivityForResult
                }
                Timber.i("FilePicker returned %s", uri)
                if ("http" == uri.scheme || "https" == uri.scheme) {
                    callback.onReceived(destination, uri, RESPONSE_URL)
                    return@startActivityForResult
                }
                if (ContentResolver.SCHEME_FILE == uri.scheme || result != Activity.RESULT_OK && result != Activity.RESULT_FIRST_USER) {
                    Toast.makeText(
                        compatActivity, R.string.file_picker_wierd, Toast.LENGTH_SHORT
                    ).show()
                }
                var inputStream: InputStream? = null
                var outputStream: OutputStream? = null
                var success = false
                try {
                    if (ContentResolver.SCHEME_FILE == uri.scheme) {
                        var path = uri.path
                        if (path!!.startsWith("/sdcard/")) { // Fix file paths
                            path =
                                Environment.getExternalStorageDirectory().absolutePath + path.substring(
                                    7
                                )
                        }
                        inputStream = SuFileInputStream.open(
                            File(path).absoluteFile
                        )
                    } else {
                        inputStream = compatActivity.contentResolver.openInputStream(uri)
                    }
                    outputStream = FileOutputStream(destination)
                    copy(inputStream!!, outputStream)
                    Timber.i("File saved at %s", destination)
                    success = true
                } catch (e: Exception) {
                    Timber.e(e)
                    Toast.makeText(
                        compatActivity, R.string.file_picker_failure, Toast.LENGTH_SHORT
                    ).show()
                } finally {
                    closeSilently(inputStream)
                    closeSilently(outputStream)
                    if (!success && destination.exists() && !destination.delete()) Timber.e("Failed to delete artefact!")
                }
                callback.onReceived(
                    destination, uri, if (success) RESPONSE_FILE else RESPONSE_ERROR
                )
            }
        }

        fun openFileTo(
            compatActivity: FoxActivity?,
            destination: File,
            callback: (File, Uri, Int) -> Unit
        ) {
            openFileTo(compatActivity!!, destination, object : OnFileReceivedCallback {
                override fun onReceived(target: File?, uri: Uri?, response: Int) {
                    if (response == RESPONSE_ERROR) {
                        MainActivity.getFoxActivity(compatActivity).runOnUiThread {
                            Toast.makeText(
                                compatActivity, R.string.no_file_provided, Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        try {
                            callback(target!!, uri!!, response)
                        } catch (e: Exception) {
                            Timber.e(e)
                            compatActivity.forceBackPressed()
                        }
                    }
                }
            })
        }
    }
}
