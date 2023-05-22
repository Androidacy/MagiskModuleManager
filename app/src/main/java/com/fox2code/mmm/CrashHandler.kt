package com.fox2code.mmm

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.fox2code.foxcompat.app.FoxActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import io.sentry.Sentry
import io.sentry.UserFeedback
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter

class CrashHandler : FoxActivity() {
    @Suppress("DEPRECATION", "KotlinConstantConditions")
    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.i("CrashHandler.onCreate(%s)", savedInstanceState)
        // log intent with extras
        Timber.d("CrashHandler.onCreate: intent=%s", intent)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_handler)
        // set crash_details MaterialTextView to the exception passed in the intent or unknown if null
        // convert stacktrace from array to string, and pretty print it (first line is the exception, the rest is the stacktrace, with each line indented by 4 spaces)
        val crashDetails = findViewById<MaterialTextView>(R.id.crash_details)
        crashDetails.text = ""
        // get the exception from the intent
        val exception = intent.getSerializableExtra("exception") as Throwable?
        // get the crashReportingEnabled from the intent
        val crashReportingEnabled = intent.getBooleanExtra("crashReportingEnabled", false)
        // if the exception is null, set the crash details to "Unknown"
        if (exception == null) {
            crashDetails.setText(R.string.crash_details)
        } else {
            // if the exception is not null, set the crash details to the exception and stacktrace
            // stacktrace is an StacktraceElement, so convert it to a string and replace the commas with newlines
            val stringWriter = StringWriter()
            exception.printStackTrace(PrintWriter(stringWriter))
            var stacktrace = stringWriter.toString()
            stacktrace = stacktrace.replace(",", "\n     ")
            crashDetails.text = getString(R.string.crash_full_stacktrace, stacktrace)
        }
        val lastEventId = intent.getStringExtra("lastEventId")
        Timber.d(
            "CrashHandler.onCreate: lastEventId=%s, crashReportingEnabled=%s",
            lastEventId,
            crashReportingEnabled
        )
        if (lastEventId == null && crashReportingEnabled) {
            // if lastEventId is null, hide the feedback button
            findViewById<View>(R.id.feedback).visibility = View.GONE
            Timber.d("CrashHandler.onCreate: lastEventId is null but crash reporting is enabled. This may indicate a bug in the crash reporting system.")
        } else {
            // if lastEventId is not null, show the feedback button
            findViewById<View>(R.id.feedback).visibility = View.VISIBLE
        }
        // disable feedback if sentry is disabled
        if (crashReportingEnabled && lastEventId != null) {
            // get name, email, and message fields
            val name = findViewById<EditText>(R.id.feedback_name)
            val email = findViewById<EditText>(R.id.feedback_email)
            val description = findViewById<EditText>(R.id.feedback_message)
            // get submit button
            findViewById<View>(R.id.feedback_submit).setOnClickListener { _: View? ->
                // require the feedback_message, rest is optional
                if (description.text.toString() == "") {
                    Toast.makeText(this, R.string.sentry_dialogue_empty_message, Toast.LENGTH_LONG)
                        .show()
                    return@setOnClickListener
                }
                // if email or name is empty, use "Anonymous"
                val nameString =
                    arrayOf(if (name.text.toString() == "") "Anonymous" else name.text.toString())
                val emailString =
                    arrayOf(if (email.text.toString() == "") "Anonymous" else email.text.toString())
                // get sentryException passed in intent
                val sentryException = intent.getSerializableExtra("sentryException") as Throwable?
                Thread {
                    try {
                        val userFeedback: UserFeedback
                        if (sentryException != null) {
                            userFeedback = UserFeedback(Sentry.captureException(sentryException))
                            // Setups the JSON body
                            if (nameString[0] == "") nameString[0] = "Anonymous"
                            if (emailString[0] == "") emailString[0] = "Anonymous"
                            userFeedback.name = nameString[0]
                            userFeedback.email = emailString[0]
                            userFeedback.comments = description.text.toString()
                            Sentry.captureUserFeedback(userFeedback)
                        }
                        Timber.i(
                            "Submitted user feedback: name %s email %s comment %s",
                            nameString[0],
                            emailString[0],
                            description.text.toString()
                        )
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                R.string.sentry_dialogue_success,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        // Close the activity
                        finish()
                        // start the main activity
                        startActivity(packageManager.getLaunchIntentForPackage(packageName))
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to submit user feedback")
                        // Show a toast if the user feedback could not be submitted
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                R.string.sentry_dialogue_failed_toast,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
            // get restart button
            findViewById<View>(R.id.restart).setOnClickListener { _: View? ->
                // Restart the app and submit sans feedback
                val sentryException = intent.getSerializableExtra("sentryException") as Throwable?
                if (crashReportingEnabled) Sentry.captureException(sentryException!!)
                finish()
                startActivity(packageManager.getLaunchIntentForPackage(packageName))
            }
        } else {
            // disable feedback if sentry is disabled
            findViewById<View>(R.id.feedback_name).isEnabled = false
            findViewById<View>(R.id.feedback_email).isEnabled = false
            findViewById<View>(R.id.feedback_message).isEnabled = false
            // fade out all the fields
            findViewById<View>(R.id.feedback_name).alpha = 0.5f
            findViewById<View>(R.id.feedback_email).alpha = 0.5f
            findViewById<View>(R.id.feedback_message).alpha = 0.5f
            // fade out the submit button
            findViewById<View>(R.id.feedback_submit).alpha = 0.5f
            // set feedback_text to "Crash reporting is disabled"
            (findViewById<View>(R.id.feedback_text) as MaterialTextView).setText(R.string.sentry_enable_nag)
            findViewById<View>(R.id.feedback_submit).setOnClickListener { _: View? ->
                Toast.makeText(
                    this,
                    R.string.sentry_dialogue_disabled,
                    Toast.LENGTH_LONG
                ).show()
            }
            // handle restart button
            // we have to explicitly enable it because it's disabled by default
            findViewById<View>(R.id.restart).isEnabled = true
            findViewById<View>(R.id.restart).setOnClickListener { _: View? ->
                // Restart the app
                finish()
                startActivity(packageManager.getLaunchIntentForPackage(packageName))
            }
        }
        // handle reset button
        findViewById<View>(R.id.reset).setOnClickListener { _: View? ->
            // show a confirmation material dialog
            val builder = MaterialAlertDialogBuilder(this)
            builder.setTitle(R.string.reset_app)
            builder.setMessage(R.string.reset_app_confirmation)
            builder.setPositiveButton(R.string.reset) { _: DialogInterface?, _: Int ->
                // reset the app
                MainApplication.getINSTANCE().resetApp()
            }
            builder.setNegativeButton(R.string.cancel) { _: DialogInterface?, _: Int -> }
            builder.show()
        }
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