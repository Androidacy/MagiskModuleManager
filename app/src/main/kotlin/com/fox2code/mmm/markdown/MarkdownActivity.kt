/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.markdown

import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.R
import com.fox2code.mmm.XHooks
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.io.net.Http
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.topjohnwu.superuser.internal.UiThreadHandler
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.io.IOException
import java.nio.charset.StandardCharsets

class MarkdownActivity : FoxActivity() {
    private var header: TextView? = null
    private var footer: TextView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(MainApplication.INSTANCE!!.tracker)
        setDisplayHomeAsUpEnabled(true)
        val intent = this.intent
        if (!MainApplication.checkSecret(intent)) {
            Timber.e("Impersonation detected!")
            forceBackPressed()
            return
        }
        val url = intent.extras?.getString(Constants.EXTRA_MARKDOWN_URL)
        var title = intent.extras!!.getString(Constants.EXTRA_MARKDOWN_TITLE)
        val config = intent.extras!!.getString(Constants.EXTRA_MARKDOWN_CONFIG)
        val changeBoot = intent.extras!!.getBoolean(Constants.EXTRA_MARKDOWN_CHANGE_BOOT)
        val needsRamdisk = intent.extras!!.getBoolean(Constants.EXTRA_MARKDOWN_NEEDS_RAMDISK)
        val minMagisk = intent.extras!!.getInt(Constants.EXTRA_MARKDOWN_MIN_MAGISK)
        val minApi = intent.extras!!.getInt(Constants.EXTRA_MARKDOWN_MIN_API)
        val maxApi = intent.extras!!.getInt(Constants.EXTRA_MARKDOWN_MAX_API)
        if (!title.isNullOrEmpty()) {
            this.title = title
        } else {
            @Suppress("UNUSED_VALUE")
            title = url
        }
        setActionBarBackground(null)
        @Suppress("DEPRECATION")
        this.window.setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, 0)
        if (!config.isNullOrEmpty()) {
            val configPkg = IntentHelper.getPackageOfConfig(config)
            try {
                XHooks.checkConfigTargetExists(this, configPkg, config)
                this.setActionBarExtraMenuButton(R.drawable.ic_baseline_app_settings_alt_24) { _: MenuItem? ->
                    IntentHelper.openConfig(this, config)
                    true
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Timber.w("Config package \"$configPkg\" missing for markdown view")
            }
        }
        // validate the url won't crash the app
        if (url.isNullOrEmpty() || url.contains("..")) {
            Timber.e("Invalid url %s", url.toString())
            forceBackPressed()
            return
        }
        Timber.i("Url for markdown %s", url.toString())
        setContentView(R.layout.markdown_view)
        val markdownBackground = findViewById<ViewGroup>(R.id.markdownBackground)
        val textView = findViewById<TextView>(R.id.markdownView)
        header = findViewById(R.id.markdownHeader)
        footer = findViewById(R.id.markdownFooter)
        updateBlurState()
        UiThreadHandler.handler.post { // Fix header/footer height
            this.updateScreenInsets(this.resources.configuration)
        }

        // Really bad created (MSG by Der_Googler)
        // set "message" to null to disable dialog
        if (changeBoot) this.addChip(MarkdownChip.CHANGE_BOOT)
        if (needsRamdisk) this.addChip(MarkdownChip.NEED_RAMDISK)
        if (minMagisk != 0) this.addChip(MarkdownChip.MIN_MAGISK, minMagisk.toString())
        if (minApi != 0) this.addChip(MarkdownChip.MIN_SDK, parseAndroidVersion(minApi))
        if (maxApi != 0) this.addChip(MarkdownChip.MAX_SDK, parseAndroidVersion(maxApi))
        Thread({
            try {
                Timber.i("Downloading")
                val rawMarkdown = getRawMarkdown(url)
                Timber.i("Encoding")
                val markdown = String(rawMarkdown, StandardCharsets.UTF_8)
                Timber.i("Done!")
                runOnUiThread {
                    footer?.minimumHeight = this.navigationBarHeight
                    MainApplication.INSTANCE!!.markwon!!.setMarkdown(
                        textView,
                        MarkdownUrlLinker.urlLinkify(markdown)
                    )
                    if (markdownBackground != null) {
                        markdownBackground.isClickable = true
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
                runOnUiThread {
                    Toast.makeText(this, R.string.failed_download, Toast.LENGTH_SHORT).show()
                }
            }
        }, "Markdown load thread").start()
    }

    private fun updateBlurState() {
        if (MainApplication.isBlurEnabled) {
            // set bottom navigation bar color to transparent blur
            val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
            if (bottomNavigationView != null) {
                bottomNavigationView.setBackgroundColor(Color.TRANSPARENT)
                bottomNavigationView.alpha = 0.8f
            } else {
                Timber.w("Bottom navigation view not found")
            }
            // set dialogs to have transparent blur
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }

    private fun updateScreenInsets() {
        runOnUiThread { this.updateScreenInsets(this.resources.configuration) }
    }

    private fun updateScreenInsets(configuration: Configuration) {
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottomInset = if (landscape) 0 else this.navigationBarHeight
        val statusBarHeight = statusBarHeight
        val actionBarHeight = actionBarHeight
        val combinedBarsHeight = statusBarHeight + actionBarHeight
        header!!.minHeight = combinedBarsHeight
        footer!!.minHeight = bottomInset
        //this.actionBarBlur.invalidate();
    }

    override fun refreshUI() {
        super.refreshUI()
        this.updateScreenInsets()
        updateBlurState()
    }

    override fun onWindowUpdated() {
        this.updateScreenInsets()
    }

    private fun addChip(markdownChip: MarkdownChip) {
        makeChip(
            this.getString(markdownChip.title),
            if (markdownChip.desc == 0) null else this.getString(markdownChip.desc)
        )
    }

    private fun addChip(markdownChip: MarkdownChip, extra: String) {
        var title = this.getString(markdownChip.title)
        title = if (title.contains("%s")) {
            title.replace("%s", extra)
        } else {
            "$title $extra"
        }
        makeChip(title, if (markdownChip.desc == 0) null else this.getString(markdownChip.desc))
    }

    private fun makeChip(title: String, message: String?) {
        val chipGroupHolder = findViewById<ChipGroup>(R.id.chip_group_holder)
        val chip = Chip(this)
        chip.text = title
        chip.visibility = View.VISIBLE
        if (message != null) {
            chip.setOnClickListener { _: View? ->
                val builder = MaterialAlertDialogBuilder(this)
                builder.setTitle(title).setMessage(message).setCancelable(true)
                    .setPositiveButton(R.string.ok) { x: DialogInterface, _: Int -> x.dismiss() }
                    .show()
            }
        }
        chipGroupHolder.addView(chip)
    }

    private fun parseAndroidVersion(version: Int): String {
        return when (version) {
            Build.VERSION_CODES.JELLY_BEAN -> "4.1 JellyBean"
            Build.VERSION_CODES.JELLY_BEAN_MR1 -> "4.2 JellyBean"
            Build.VERSION_CODES.JELLY_BEAN_MR2 -> "4.3 JellyBean"
            Build.VERSION_CODES.KITKAT -> "4.4 KitKat"
            Build.VERSION_CODES.KITKAT_WATCH -> "4.4 KitKat Watch"
            Build.VERSION_CODES.LOLLIPOP -> "5.0 Lollipop"
            Build.VERSION_CODES.LOLLIPOP_MR1 -> "5.1 Lollipop"
            Build.VERSION_CODES.M -> "6.0 Marshmallow"
            Build.VERSION_CODES.N -> "7.0 Nougat"
            Build.VERSION_CODES.N_MR1 -> "7.1 Nougat"
            Build.VERSION_CODES.O -> "8.0 Oreo"
            Build.VERSION_CODES.O_MR1 -> "8.1 Oreo"
            Build.VERSION_CODES.P -> "9.0 Pie"
            Build.VERSION_CODES.Q -> "10 (Q)"
            Build.VERSION_CODES.R -> "11 (R)"
            Build.VERSION_CODES.S -> "12 (S)"
            Build.VERSION_CODES.S_V2 -> "12L"
            Build.VERSION_CODES.TIRAMISU -> "13 Tiramisu"
            else -> "Sdk: $version"
        }
    }

    override fun onResume() {
        super.onResume()
        val footer = findViewById<View>(R.id.markdownFooter)
        if (footer != null) footer.minimumHeight = this.navigationBarHeight
    }

    companion object {
        private val redirects = HashMap<String, String>(4)
        private val variants = arrayOf("readme.md", "README.MD", ".github/README.md")

        @Throws(IOException::class)
        private fun getRawMarkdown(url: String): ByteArray {
            var newUrl = redirects[url]
            return if (newUrl != null && newUrl != url) {
                Http.doHttpGet(newUrl, true)
            } else try {
                Http.doHttpGet(url, true)
            } catch (e: IOException) {
                // Workaround GitHub README.md case sensitivity issue
                if (url.startsWith("https://raw.githubusercontent.com/") && url.endsWith("/README.md")) {
                    val prefix = url.substring(0, url.length - 9)
                    for (suffix in variants) {
                        newUrl = prefix + suffix
                        try { // Try with lowercase version
                            val rawMarkdown = Http.doHttpGet(prefix + suffix, true)
                            redirects[url] = newUrl // Avoid retries
                            return rawMarkdown
                        } catch (ignored: IOException) {
                        }
                    }
                }
                throw e
            }
        }
    }
}