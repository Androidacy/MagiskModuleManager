/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler : AppCompatActivity() {
    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (MainApplication.forceDebugLogging) Timber.i("CrashHandler.onCreate(%s)", savedInstanceState)
        // log intent with extras
        if (MainApplication.forceDebugLogging) Timber.d("CrashHandler.onCreate: intent=%s", intent)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_handler)
        // set crash_details MaterialTextView to the exception passed in the intent or unknown if null
        // convert stacktrace from array to string, and pretty print it (first line is the exception, the rest is the stacktrace, with each line indented by 4 spaces)
        val crashDetails = findViewById<MaterialTextView>(R.id.crash_details)
        crashDetails.text = ""
        // get the exception from the intent
        val exceptionFromIntent = intent.getSerializableExtra("exception") as Throwable?
        var exception: String? = null
        // parse the exception from the intent into exception if it is not null
        if (exceptionFromIntent != null) {
            val stringWriter = StringWriter()
            exceptionFromIntent.printStackTrace(PrintWriter(stringWriter))
            var stacktrace = stringWriter.toString()
            stacktrace = stacktrace.replace(",", "\n     ")
            exception = stacktrace
        }
        val sharedPreferences = MainApplication.getPreferences("mmm")
        if (exception == null && sharedPreferences != null) {
            exception = sharedPreferences.getString("pref_crash_stacktrace", null)
        }
        // if the exception is null, set the crash details to "Unknown"
        if (exception == null) {
            crashDetails.setText(R.string.crash_details)
        } else {
            crashDetails.text = getString(R.string.crash_full_stacktrace, exception)
        }
        // handle reset button
        findViewById<View>(R.id.reset).setOnClickListener { _: View? ->
            // show a confirmation material dialog
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.reset_app)
            builder.setMessage(R.string.reset_app_confirmation)
            builder.setPositiveButton(R.string.reset) { _: DialogInterface?, _: Int ->
                // reset the app
                MainApplication.INSTANCE!!.resetApp()
            }
            builder.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
            builder.show()
        }
        // restart button
        findViewById<View>(R.id.restart).setOnClickListener { _: View? ->
            // restart the app
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        }
        // remove pref_crashed from shared preferences
        sharedPreferences?.edit()?.remove("pref_crashed")?.apply()
    }

    fun copyCrashDetails(view: View) {
        // change view to a checkmark
        view.setBackgroundResource(R.drawable.baseline_check_24)
        // copy crash_details to clipboard
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val crashDetails =
            (findViewById<View>(R.id.crash_details) as MaterialTextView).text.toString()
        clipboard.setPrimaryClip(ClipData.newPlainText("crash_details", crashDetails))
        // show a toast
        Toast.makeText(this, R.string.crash_details_copied, Toast.LENGTH_LONG).show()
        // after 1 second, change the view back to a copy button
        Thread {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runOnUiThread { view.setBackgroundResource(R.drawable.baseline_copy_all_24) }
        }.start()
    }
}