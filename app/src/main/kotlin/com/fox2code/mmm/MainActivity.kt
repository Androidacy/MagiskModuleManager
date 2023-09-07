/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
import com.fox2code.foxcompat.app.FoxActivity
import com.fox2code.foxcompat.view.FoxDisplay
import com.fox2code.mmm.AppUpdateManager.Companion.appUpdateManager
import com.fox2code.mmm.OverScrollManager.OverScrollHelper
import com.fox2code.mmm.androidacy.AndroidacyRepoData
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityCreate
import com.fox2code.mmm.background.BackgroundUpdateChecker.Companion.onMainActivityResume
import com.fox2code.mmm.installer.InstallerInitializer
import com.fox2code.mmm.installer.InstallerInitializer.Companion.errorNotification
import com.fox2code.mmm.installer.InstallerInitializer.Companion.peekMagiskVersion
import com.fox2code.mmm.installer.InstallerInitializer.Companion.tryGetMagiskPathAsync
import com.fox2code.mmm.manager.LocalModuleInfo
import com.fox2code.mmm.manager.ModuleInfo
import com.fox2code.mmm.manager.ModuleManager.Companion.instance
import com.fox2code.mmm.module.ModuleViewAdapter
import com.fox2code.mmm.module.ModuleViewListBuilder
import com.fox2code.mmm.repo.RepoManager
import com.fox2code.mmm.repo.RepoModule
import com.fox2code.mmm.settings.SettingsActivity
import com.fox2code.mmm.utils.ExternalHelper
import com.fox2code.mmm.utils.RuntimeUtils
import com.fox2code.mmm.utils.SyncManager
import com.fox2code.mmm.utils.io.net.Http.Companion.cleanDnsCache
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.fox2code.mmm.utils.room.ReposListDatabase
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import org.matomo.sdk.extra.TrackHelper
import timber.log.Timber
import java.sql.Timestamp


class MainActivity : FoxActivity(), OnRefreshListener, OverScrollHelper {
    val moduleViewListBuilder: ModuleViewListBuilder = ModuleViewListBuilder(this)
    val moduleViewListBuilderOnline: ModuleViewListBuilder = ModuleViewListBuilder(this)
    var progressIndicator: LinearProgressIndicator? = null
    private var moduleViewAdapter: ModuleViewAdapter? = null
    private var moduleViewAdapterOnline: ModuleViewAdapter? = null
    private var swipeRefreshLayout: SwipeRefreshLayout? = null
    private var swipeRefreshLayoutOrigStartOffset = 0
    private var swipeRefreshLayoutOrigEndOffset = 0
    private var swipeRefreshBlocker: Long = 0
    override var overScrollInsetTop = 0
        private set
    override var overScrollInsetBottom = 0
        private set
    private var moduleList: RecyclerView? = null
    private var moduleListOnline: RecyclerView? = null
    private var searchTextInputEditText: TextInputEditText? = null
    private var rebootFab: FloatingActionButton? = null
    private var initMode = false
    private var runtimeUtils: RuntimeUtils? = null

    init {
        moduleViewListBuilder.addNotification(NotificationType.INSTALL_FROM_STORAGE)
    }

    override fun onResume() {
        onMainActivityResume(this)
        super.onResume()
    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        initMode = true
        if (doSetupRestarting) {
            doSetupRestarting = false
        }
        onMainActivityCreate(this)
        super.onCreate(savedInstanceState)
        TrackHelper.track().screen(this).with(MainApplication.INSTANCE!!.tracker)
        // hide this behind a buildconfig flag for now, but crash the app if it's not an official build and not debug
        if (BuildConfig.ENABLE_PROTECTION && !MainApplication.o && !BuildConfig.DEBUG) {
            throw RuntimeException("This is not an official build of AMM")
        } else if (!MainApplication.o && !BuildConfig.DEBUG) {
            Timber.w("You may be running an untrusted build.")
            // Show a toast to warn the user
            Toast.makeText(this, R.string.not_official_build, Toast.LENGTH_LONG).show()
        }
        // track enabled repos
        Thread {
            val db = Room.databaseBuilder(
                applicationContext, ReposListDatabase::class.java, "ReposList.db"
            ).build()
            val repoDao = db.reposListDao()
            val repos = repoDao.getAll()
            val enabledRepos = StringBuilder()
            for (repo in repos) {
                if (repo.enabled) {
                    enabledRepos.append(repo.url).append(", ")
                }
            }
            db.close()
            if (enabledRepos.isNotEmpty()) {
                enabledRepos.delete(enabledRepos.length - 2, enabledRepos.length)
                TrackHelper.track().event("Enabled Repos", enabledRepos.toString())
                    .with(MainApplication.INSTANCE!!.tracker)
            }
        }.start()
        val ts = Timestamp(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        val buildTime = Timestamp(BuildConfig.BUILD_TIME)
        if (BuildConfig.DEBUG) {
            if (ts.time > buildTime.time) {
                val pm = packageManager
                val intent = Intent(this, ExpiredActivity::class.java)
                val resolveInfo = pm.queryIntentActivities(intent, 0)
                if (resolveInfo.size > 0) {
                    startActivity(intent)
                    finish()
                    return
                } else {
                    throw IllegalAccessError("This build has expired")
                }
            }
        } else {
            val ts2 = Timestamp(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)
            if (ts2.time > buildTime.time) {
                Toast.makeText(this, R.string.build_expired, Toast.LENGTH_LONG).show()
            }
        }
        setContentView(R.layout.activity_main)
        this.setTitle(R.string.app_name_v2)
        // set window flags to ignore status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // ignore status bar space
            this.window.setDecorFitsSystemWindows(false)
        } else {
            this.window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val layoutParams = this.window.attributes
            layoutParams.layoutInDisplayCutoutMode =  // Support cutout in Android 9
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            this.window.attributes = layoutParams
        }
        progressIndicator = findViewById(R.id.progress_bar)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh)
        val swipeRefreshLayout = swipeRefreshLayout!!
        swipeRefreshLayoutOrigStartOffset = swipeRefreshLayout.progressViewStartOffset
        swipeRefreshLayoutOrigEndOffset = swipeRefreshLayout.progressViewEndOffset
        swipeRefreshBlocker = Long.MAX_VALUE
        moduleList = findViewById(R.id.module_list)
        moduleListOnline = findViewById(R.id.module_list_online)
        searchTextInputEditText = findViewById(R.id.search_input)
        val textInputEditText = searchTextInputEditText!!
        // set search view listeners for text edit. filter the appropriate list based on visibility. do the filtering as the user types not just on submit as a background task
        textInputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence, start: Int, count: Int, after: Int
            ) {
                // do nothing
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                // do nothing
            }

            override fun afterTextChanged(s: Editable) {
                // filter the appropriate list based on visibility
                if (initMode) return
                val query = s.toString()
                TrackHelper.track().search(query).with(MainApplication.INSTANCE!!.tracker)
                Thread {
                    if (moduleViewListBuilder.setQueryChange(query)) {
                        Timber.i("Query submit: %s on offline list", query)
                        Thread(
                            { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                            "Query update thread"
                        ).start()
                    }
                    // same for online list
                    if (moduleViewListBuilderOnline.setQueryChange(query)) {
                        Timber.i("Query submit: %s on online list", query)
                        Thread({
                            moduleViewListBuilderOnline.applyTo(
                                moduleListOnline!!, moduleViewAdapterOnline!!
                            )
                        }, "Query update thread").start()
                    }
                }.start()
            }
        })
        // set on submit listener for search view. filter the appropriate list based on visibility
        textInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // filter the appropriate list based on visibility
                val query = textInputEditText.text.toString()
                TrackHelper.track().search(query).with(MainApplication.INSTANCE!!.tracker)
                Thread {
                    if (moduleViewListBuilder.setQueryChange(query)) {
                        Timber.i("Query submit: %s on offline list", query)
                        Thread(
                            { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                            "Query update thread"
                        ).start()
                    }
                    // same for online list
                    if (moduleViewListBuilderOnline.setQueryChange(query)) {
                        Timber.i("Query submit: %s on online list", query)
                        Thread({
                            moduleViewListBuilderOnline.applyTo(
                                moduleListOnline!!, moduleViewAdapterOnline!!
                            )
                        }, "Query update thread").start()
                    }
                }.start()
                // hide keyboard
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(textInputEditText.windowToken, 0)
                true
            } else {
                false
            }
        }
        // set listener so when user clicks outside of search view, it loses focus
        textInputEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                textInputEditText.clearFocus()
            }
        }

        moduleViewAdapter = ModuleViewAdapter()
        moduleViewAdapterOnline = ModuleViewAdapter()
        val moduleList = moduleList!!
        val moduleListOnline = moduleListOnline!!
        moduleList.adapter = moduleViewAdapter
        moduleListOnline.adapter = moduleViewAdapterOnline
        moduleList.layoutManager = LinearLayoutManager(this)
        moduleListOnline.layoutManager = LinearLayoutManager(this)
        moduleList.setItemViewCacheSize(4) // Default is 2
        swipeRefreshLayout.setOnRefreshListener(this)
        runtimeUtils = RuntimeUtils()
        // add background blur if enabled
        updateBlurState()
        //hideActionBar();
        runtimeUtils!!.checkShowInitialSetup(this, this)
        rebootFab = findViewById(R.id.reboot_fab)
        val rebootFab = rebootFab!!

        // set on click listener for reboot fab
        rebootFab.setOnClickListener {
            // show reboot dialog with options to reboot, reboot to recovery, bootloader, or edl, and use RuntimeUtils to reboot
            val rebootDialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.reboot)
                .setItems(
                    arrayOf(
                        getString(R.string.reboot),
                        getString(R.string.reboot_recovery),
                        getString(R.string.reboot_bootloader),
                        getString(R.string.reboot_edl)
                    )
                ) { _: DialogInterface?, which: Int ->
                    when (which) {
                        0 -> RuntimeUtils.reboot(this@MainActivity, RuntimeUtils.RebootMode.REBOOT)
                        1 -> RuntimeUtils.reboot(
                            this@MainActivity,
                            RuntimeUtils.RebootMode.RECOVERY
                        )

                        2 -> RuntimeUtils.reboot(
                            this@MainActivity,
                            RuntimeUtils.RebootMode.BOOTLOADER
                        )

                        3 -> RuntimeUtils.reboot(this@MainActivity, RuntimeUtils.RebootMode.EDL)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            rebootDialog.show()
        }
        // get background color and elevation of reboot fab
        moduleList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) textInputEditText.clearFocus()
                // hide search view  and reboot fab when scrolling - we have to account for padding, corners, and shadows
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    rebootFab.animate().translationY(rebootFab.height.toFloat() + 2 * 8 + 2 * 2)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the search bar
                if (dy < 0) {
                    rebootFab.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f))
                        .start()
                }
            }
        })
        // same for online
        moduleListOnline.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) textInputEditText.clearFocus()
                // hide search view when scrolling
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    rebootFab.animate().translationY(rebootFab.height.toFloat() + 2 * 8 + 2 * 2)
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the search bar
                if (dy < 0) {
                    rebootFab.animate().translationY(0f)
                }
            }
        })
        textInputEditText.minimumHeight = FoxDisplay.dpToPixel(16f)
        textInputEditText.imeOptions =
            EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN
        textInputEditText.isEnabled = false // Enabled later
        this.updateScreenInsets(this.resources.configuration)

        // on the bottom nav, there's a settings item. open the settings activity when it's clicked.
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.settings_menu_item -> {
                    TrackHelper.track().event("view_list", "settings")
                        .with(MainApplication.INSTANCE!!.tracker)
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }

                R.id.online_menu_item -> {
                    TrackHelper.track().event("view_list", "online_modules")
                        .with(MainApplication.INSTANCE!!.tracker)
                    searchTextInputEditText!!.clearFocus()
                    searchTextInputEditText!!.text?.clear()
                    // set module_list_online as visible and module_list as gone. fade in/out
                    moduleListOnline.alpha = 0f
                    moduleListOnline.visibility = View.VISIBLE
                    moduleListOnline.animate().alpha(1f).setDuration(300).setListener(null)
                    moduleList.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                moduleList.visibility = View.GONE
                            }
                        })
                    textInputEditText.clearFocus()
                    // empty input for text input
                    textInputEditText.text?.clear()
                    // reset reboot and search card
                    rebootFab.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f))
                }

                R.id.installed_menu_item -> {
                    TrackHelper.track().event("view_list", "installed_modules")
                        .with(MainApplication.INSTANCE!!.tracker)
                    searchTextInputEditText!!.clearFocus()
                    searchTextInputEditText!!.text?.clear()
                    // set module_list_online as gone and module_list as visible. fade in/out
                    moduleList.alpha = 0f
                    moduleList.visibility = View.VISIBLE
                    moduleList.animate().alpha(1f).setDuration(300).setListener(null)
                    moduleListOnline.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                moduleListOnline.visibility = View.GONE
                            }
                        })
                    // set search view to cleared
                    textInputEditText.clearFocus()
                    textInputEditText.text?.clear()
                    // reset reboot and search card
                    rebootFab.animate().translationY(0f).setInterpolator(DecelerateInterpolator(2f))
                }
            }
            true
        }
        // parse intent. if action is SHOW_ONLINE, show online modules
        val action = intent.action
        if (action == "android.intent.action.SHOW_ONLINE") {
            // select online modules
            bottomNavigationView.selectedItemId = R.id.online_menu_item
        } else {
            bottomNavigationView.selectedItemId = R.id.installed_menu_item
        }
        // update the padding of blur_frame to match the new bottom nav height
        val blurFrame = findViewById<View>(R.id.blur_frame)
        blurFrame.post {
            blurFrame.setPadding(
                blurFrame.paddingLeft,
                blurFrame.paddingTop,
                blurFrame.paddingRight,
                bottomNavigationView.height
            )
        }
        // for some reason, root_container has a margin at the top. remove it.
        val rootContainer = findViewById<View>(R.id.root_container)
        rootContainer.post {
            val params = rootContainer.layoutParams as MarginLayoutParams
            params.topMargin = 0
            rootContainer.layoutParams = params
            rootContainer.y = 0f
        }
        // reset update module and update module count in main application
        MainApplication.INSTANCE!!.resetUpdateModule()
        tryGetMagiskPathAsync(object : InstallerInitializer.Callback {
            override fun onPathReceived(path: String?) {
                Timber.i("Got magisk path: %s", path)
                if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND) {
                    if (!InstallerInitializer.isKsu) {
                        moduleViewListBuilder.addNotification(
                            NotificationType.MAGISK_OUTDATED
                        )
                    } else {
                        moduleViewListBuilder.addNotification(
                            NotificationType.KSU_EXPERIMENTAL
                        )
                    }
                }
                if (!MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.INSTALL_FROM_STORAGE
                )
                instance!!.scan()
                instance!!.runAfterScan { moduleViewListBuilder.appendInstalledModules() }
                instance!!.runAfterScan { moduleViewListBuilderOnline.appendRemoteModules() }
                commonNext()
            }

            override fun onFailure(error: Int) {
                Timber.e("Failed to get magisk path!")
                moduleViewListBuilder.addNotification(errorNotification)
                moduleViewListBuilderOnline.addNotification(errorNotification)
                commonNext()
            }

            fun commonNext() {
                if (BuildConfig.DEBUG) {
                    if (BuildConfig.DEBUG) Timber.d("Common next")
                    moduleViewListBuilder.addNotification(NotificationType.DEBUG)
                }
                NotificationType.NO_INTERNET.autoAdd(moduleViewListBuilderOnline)
                val progressIndicator = progressIndicator!!
                // hide progress bar is repo-manager says we have no internet
                if (!RepoManager.getINSTANCE()!!.hasConnectivity()) {
                    Timber.i("No connection, hiding progress")
                    runOnUiThread {
                        progressIndicator.visibility = View.GONE
                        progressIndicator.isIndeterminate = false
                        progressIndicator.max = PRECISION
                    }
                }
                updateScreenInsets() // Fix an edge case
                val context: Context = this@MainActivity
                if (runtimeUtils!!.waitInitialSetupFinished(context, this@MainActivity)) {
                    if (BuildConfig.DEBUG) Timber.d("waiting...")
                    return
                }
                swipeRefreshBlocker = System.currentTimeMillis() + 5000L
                if (MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.SHOWCASE_MODE
                )
                if (!hasWebView()) {
                    // Check Http for WebView availability
                    moduleViewListBuilder.addNotification(NotificationType.NO_WEB_VIEW)
                    // disable online tab
                    runOnUiThread {
                        bottomNavigationView.menu.getItem(1).isEnabled = false
                        bottomNavigationView.selectedItemId = R.id.installed_menu_item
                    }
                }
                moduleViewListBuilder.applyTo(moduleList, moduleViewAdapter!!)
                Timber.i("Scanning for modules!")
                if (BuildConfig.DEBUG) Timber.i("Initialize Update")
                val max = instance!!.getUpdatableModuleCount()
                if (RepoManager.getINSTANCE()!!.customRepoManager != null && RepoManager.getINSTANCE()!!.customRepoManager!!.needUpdate()) {
                    Timber.w("Need update on create")
                } else if (RepoManager.getINSTANCE()!!.customRepoManager == null) {
                    Timber.w("CustomRepoManager is null")
                }
                // update compat metadata
                if (BuildConfig.DEBUG) Timber.i("Check Update Compat")
                appUpdateManager.checkUpdateCompat()
                if (BuildConfig.DEBUG) Timber.i("Check Update")
                // update repos
                if (hasWebView()) {
                    val updateListener: SyncManager.UpdateListener =
                        object : SyncManager.UpdateListener {
                            override fun update(value: Int) {
                                runOnUiThread(if (max == 0) Runnable {
                                    progressIndicator.setProgressCompat(
                                        value, true
                                    )
                                } else Runnable {
                                    progressIndicator.setProgressCompat(
                                        value, true
                                    )
                                })
                            }
                        }
                    RepoManager.getINSTANCE()!!.update(updateListener)
                }
                // various notifications
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilderOnline)
                NotificationType.DEBUG.autoAdd(moduleViewListBuilder)
                NotificationType.DEBUG.autoAdd(moduleViewListBuilderOnline)
                if (hasWebView() && !NotificationType.REPO_UPDATE_FAILED.shouldRemove()) {
                    moduleViewListBuilder.addNotification(NotificationType.REPO_UPDATE_FAILED)
                } else {
                    if (!hasWebView()) {
                        runOnUiThread {
                            progressIndicator.setProgressCompat(PRECISION, true)
                            progressIndicator.visibility = View.GONE
                            textInputEditText.isEnabled = false
                            updateScreenInsets(resources.configuration)
                        }
                        return
                    }
                    // Compatibility data still needs to be updated
                    val appUpdateManager = appUpdateManager
                    if (BuildConfig.DEBUG) Timber.i("Check App Update")
                    if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                        NotificationType.UPDATE_AVAILABLE
                    )
                    if (BuildConfig.DEBUG) Timber.i("Check Json Update")
                    if (max != 0) {
                        var current = 0
                        for (localModuleInfo in instance!!.modules.values) {
                            // if it has updateJson and FLAG_MM_REMOTE_MODULE is not set on flags, check for json update
                            // this is a dirty hack until we better store if it's a remote module
                            // the reasoning is that remote repos are considered "validated" while local modules are not
                            // for instance, a potential attacker could hijack a perfectly legitimate module and inject an updateJson with a malicious update - thereby bypassing any checks repos may have, without anyone noticing until it's too late
                            if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                                if (BuildConfig.DEBUG) Timber.i(localModuleInfo.id)
                                try {
                                    localModuleInfo.checkModuleUpdate()
                                } catch (e: Exception) {
                                    Timber.e(e)
                                }
                                current++
                                val currentTmp = current
                                runOnUiThread {
                                    progressIndicator.setProgressCompat(
                                        currentTmp / max,
                                        true
                                    )
                                }
                            }
                        }
                    }
                }
                if (BuildConfig.DEBUG) Timber.i("Apply")
                RepoManager.getINSTANCE()
                    ?.runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
                moduleViewListBuilder.applyTo(moduleList, moduleViewAdapter!!)
                moduleViewListBuilder.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                moduleViewListBuilderOnline.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                // if moduleViewListBuilderOnline has the upgradeable notification, show a badge on the online repo nav item
                if (MainApplication.INSTANCE!!.modulesHaveUpdates) {
                    Timber.i("Applying badge")
                    Handler(Looper.getMainLooper()).post {
                        val badge = bottomNavigationView.getOrCreateBadge(R.id.online_menu_item)
                        badge.isVisible = true
                        badge.number = MainApplication.INSTANCE!!.updateModuleCount
                        badge.applyTheme(MainApplication.INSTANCE!!.theme)
                        Timber.i("Badge applied")
                    }
                }
                runOnUiThread {
                    progressIndicator.setProgressCompat(PRECISION, true)
                    progressIndicator.visibility = View.GONE
                    textInputEditText.isEnabled = true
                    updateScreenInsets(resources.configuration)
                }
                maybeShowUpgrade()
                Timber.i("Finished app opening state!")
            }
        }, true)
        // if system lang is not in MainApplication.supportedLocales, show a snackbar to ask user to help translate
        if (!MainApplication.supportedLocales.contains(this.resources.configuration.locales[0].language)) {
            // call showWeblateSnackbar() with language code and language name
            runtimeUtils!!.showWeblateSnackbar(
                this,
                this,
                this.resources.configuration.locales[0].language,
                this.resources.configuration.locales[0].displayLanguage
            )
        }
        ExternalHelper.INSTANCE.refreshHelper(this)
        initMode = false
    }

    fun updateScreenInsets() {
        runOnUiThread { this.updateScreenInsets(this.resources.configuration) }
    }

    private fun updateScreenInsets(configuration: Configuration) {
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val bottomInset = if (landscape) 0 else this.navigationBarHeight
        val statusBarHeight = statusBarHeight + FoxDisplay.dpToPixel(2f)
        swipeRefreshLayout!!.setProgressViewOffset(
            false,
            swipeRefreshLayoutOrigStartOffset + statusBarHeight,
            swipeRefreshLayoutOrigEndOffset + statusBarHeight
        )
        moduleViewListBuilder.setHeaderPx(statusBarHeight)
        moduleViewListBuilderOnline.setHeaderPx(statusBarHeight)
        moduleViewListBuilder.updateInsets()
        //this.actionBarBlur.invalidate();
        overScrollInsetTop = statusBarHeight
        overScrollInsetBottom = bottomInset
        // set root_container to have zero padding
        findViewById<View>(R.id.root_container).setPadding(0, statusBarHeight, 0, 0)
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

    override fun refreshUI() {
        super.refreshUI()
        if (initMode) return
        initMode = true
        Timber.i("Item Before")
        searchTextInputEditText!!.clearFocus()
        searchTextInputEditText!!.text?.clear()
        this.updateScreenInsets()
        updateBlurState()
        moduleViewListBuilder.setQuery(null)
        Timber.i("Item After")
        moduleViewListBuilder.refreshNotificationsUI(moduleViewAdapter!!)
        tryGetMagiskPathAsync(object : InstallerInitializer.Callback {
            override fun onPathReceived(path: String?) {
                val context: Context = this@MainActivity
                val mainActivity = this@MainActivity
                runtimeUtils!!.checkShowInitialSetup(context, mainActivity)
                // Wait for doSetupNow to finish
                while (doSetupNowRunning) {
                    try {
                        Thread.sleep(100)
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND) moduleViewListBuilder.addNotification(
                    NotificationType.MAGISK_OUTDATED
                )
                if (!MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.INSTALL_FROM_STORAGE
                )
                instance!!.scan()
                instance!!.runAfterScan { moduleViewListBuilder.appendInstalledModules() }
                commonNext()
            }

            override fun onFailure(error: Int) {
                Timber.e("Error: %s", error)
                moduleViewListBuilder.addNotification(errorNotification)
                moduleViewListBuilderOnline.addNotification(errorNotification)
                commonNext()
            }

            fun commonNext() {
                Timber.i("Common Before")
                if (MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.SHOWCASE_MODE
                )
                NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilderOnline)
                NotificationType.NO_INTERNET.autoAdd(moduleViewListBuilderOnline)
                if (appUpdateManager.checkUpdate(false)) moduleViewListBuilder.addNotification(
                    NotificationType.UPDATE_AVAILABLE
                )
                RepoManager.getINSTANCE()!!.updateEnabledStates()
                if (RepoManager.getINSTANCE()!!.customRepoManager!!.needUpdate()) {
                    runOnUiThread {
                        progressIndicator!!.isIndeterminate = false
                        progressIndicator!!.max = PRECISION
                    }
                    if (BuildConfig.DEBUG) Timber.i("Check Update")
                    val updateListener: SyncManager.UpdateListener =
                        object : SyncManager.UpdateListener {
                            override fun update(value: Int) {
                                runOnUiThread {
                                    progressIndicator!!.setProgressCompat(
                                        value, true
                                    )
                                }
                            }
                        }
                    RepoManager.getINSTANCE()!!.update(updateListener)
                    runOnUiThread {
                        progressIndicator!!.setProgressCompat(PRECISION, true)
                        progressIndicator!!.visibility = View.GONE
                    }
                }
                if (BuildConfig.DEBUG) Timber.i("Apply")
                RepoManager.getINSTANCE()
                    ?.runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
                Timber.i("Common Before applyTo")
                moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!)
                moduleViewListBuilderOnline.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
                Timber.i("Common After")
            }
        })
        initMode = false
    }

    override fun onWindowUpdated() {
        this.updateScreenInsets()
    }

    override fun onRefresh() {
        if (swipeRefreshBlocker > System.currentTimeMillis() || initMode || progressIndicator == null || progressIndicator!!.visibility == View.VISIBLE || doSetupNowRunning) {
            swipeRefreshLayout!!.isRefreshing = false
            return  // Do not double scan
        }
        if (BuildConfig.DEBUG) Timber.i("Refresh")
        progressIndicator!!.visibility = View.VISIBLE
        progressIndicator!!.setProgressCompat(0, false)
        swipeRefreshBlocker = System.currentTimeMillis() + 5000L

        MainApplication.INSTANCE!!.repoModules.clear()
        // this.swipeRefreshLayout.setRefreshing(true); ??
        Thread({
            cleanDnsCache() // Allow DNS reload from network
            val max = instance!!.getUpdatableModuleCount()
            val updateListener: SyncManager.UpdateListener = object : SyncManager.UpdateListener {
                override fun update(value: Int) {
                    runOnUiThread(if (max == 0) Runnable {
                        progressIndicator!!.setProgressCompat(
                            value, true
                        )
                    } else Runnable {
                        progressIndicator!!.setProgressCompat(
                            value, true
                        )
                    })
                }
            }
            RepoManager.getINSTANCE()!!.update(updateListener)
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            if (!NotificationType.NO_INTERNET.shouldRemove()) {
                moduleViewListBuilderOnline.addNotification(NotificationType.NO_INTERNET)
            } else if (!NotificationType.REPO_UPDATE_FAILED.shouldRemove()) {
                moduleViewListBuilder.addNotification(NotificationType.REPO_UPDATE_FAILED)
            } else {
                // Compatibility data still needs to be updated
                val appUpdateManager = appUpdateManager
                if (BuildConfig.DEBUG) Timber.i("Check App Update")
                if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                    NotificationType.UPDATE_AVAILABLE
                )
                if (BuildConfig.DEBUG) Timber.i("Check Json Update")
                if (max != 0) {
                    var current = 0
                    for (localModuleInfo in instance!!.modules.values) {
                        if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                            if (BuildConfig.DEBUG) Timber.i(localModuleInfo.id)
                            try {
                                localModuleInfo.checkModuleUpdate()
                            } catch (e: Exception) {
                                Timber.e(e)
                            }
                            current++
                            val currentTmp = current
                            runOnUiThread {
                                progressIndicator!!.setProgressCompat(
                                    currentTmp / max,
                                    true
                                )
                            }
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) Timber.i("Apply")
            runOnUiThread {
                progressIndicator!!.visibility = View.GONE
                swipeRefreshLayout!!.isRefreshing = false
            }
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            RepoManager.getINSTANCE()!!.updateEnabledStates()
            RepoManager.getINSTANCE()
                ?.runAfterUpdate { moduleViewListBuilder.appendInstalledModules() }
            RepoManager.getINSTANCE()
                ?.runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
            moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!)
            moduleViewListBuilderOnline.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
        }, "Repo update thread").start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.updateScreenInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        this.updateScreenInsets()
    }

    fun maybeShowUpgrade() {
        if (AndroidacyRepoData.instance.memberLevel == null) {
            // wait for up to 10 seconds for AndroidacyRepoData to be initialized
            var i: Int
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == null) {
                if (BuildConfig.DEBUG) Timber.d("Member level is null, waiting for it to be initialized")
                i = 0
                while (AndroidacyRepoData.instance.memberLevel == null && i < 20) {
                    i++
                    try {
                        Thread.sleep(500)
                    } catch (e: InterruptedException) {
                        Timber.e(e)
                    }
                }
            }
            // if it's still null, but it's enabled, throw an error
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == null) {
                Timber.e("AndroidacyRepoData is enabled, but member level is null")
            }
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == "Guest") {
                runtimeUtils!!.showUpgradeSnackbar(this, this)
            } else {
                if (!AndroidacyRepoData.instance.isEnabled) {
                    Timber.i("AndroidacyRepoData is disabled, not showing upgrade snackbar 1")
                } else if (AndroidacyRepoData.instance.memberLevel != "Guest") {
                    Timber.i(
                        "AndroidacyRepoData is not Guest, not showing upgrade snackbar 1. Level: %s",
                        AndroidacyRepoData.instance.memberLevel
                    )
                } else {
                    Timber.i("Unknown error, not showing upgrade snackbar 1")
                }
            }
        } else if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == "Guest") {
            runtimeUtils!!.showUpgradeSnackbar(this, this)
        } else {
            if (!AndroidacyRepoData.instance.isEnabled) {
                Timber.i("AndroidacyRepoData is disabled, not showing upgrade snackbar 2")
            } else if (AndroidacyRepoData.instance.memberLevel != "Guest") {
                Timber.i(
                    "AndroidacyRepoData is not Guest, not showing upgrade snackbar 2. Level: %s",
                    AndroidacyRepoData.instance.memberLevel
                )
            } else {
                Timber.i("Unknown error, not showing upgrade snackbar 2")
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                val outRect = Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    v.clearFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    companion object {
        fun getFoxActivity(activity: FoxActivity): FoxActivity {
            return activity
        }

        fun getFoxActivity(context: Context): FoxActivity {
            return context as FoxActivity
        }

        private const val PRECISION = 100

        @JvmField
        var doSetupNowRunning = true
        var doSetupRestarting = false
        var localModuleInfoList: List<LocalModuleInfo> = ArrayList()
        var onlineModuleInfoList: List<RepoModule> = ArrayList()
        var isShowingWeblateSb = false // race condition
    }
}