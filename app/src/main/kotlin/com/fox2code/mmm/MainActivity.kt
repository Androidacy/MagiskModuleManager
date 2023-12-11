/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout.OnRefreshListener
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
import com.fox2code.mmm.utils.IntentHelper
import com.fox2code.mmm.utils.RuntimeUtils
import com.fox2code.mmm.utils.SyncManager
import com.fox2code.mmm.utils.io.Files
import com.fox2code.mmm.utils.io.net.Http.Companion.cleanDnsCache
import com.fox2code.mmm.utils.io.net.Http.Companion.hasWebView
import com.fox2code.mmm.utils.room.ReposListDatabase
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.topjohnwu.superuser.io.SuFileInputStream
import ly.count.android.sdk.Countly
import ly.count.android.sdk.ModuleFeedback
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files.*
import java.sql.Timestamp
import kotlin.math.roundToInt


class MainActivity : AppCompatActivity(), OnRefreshListener, OverScrollHelper {
    private lateinit var bottomNavigationView: BottomNavigationView
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
    var callback: IntentHelper.Companion.OnFileReceivedCallback? = null
    var destination: File? = null


    @SuppressLint("SdCardPath")
    val getContent = this.registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) {
            Timber.d("invalid uri received")
            callback?.onReceived(destination, null, IntentHelper.RESPONSE_ERROR)
            return@registerForActivityResult
        }
        if (MainApplication.forceDebugLogging) Timber.i("FilePicker returned %s", uri)
        if ("http" == uri.scheme || "https" == uri.scheme) {
            callback?.onReceived(destination, uri, IntentHelper.RESPONSE_URL)
            return@registerForActivityResult
        }
        if (ContentResolver.SCHEME_FILE == uri.scheme) {
            Toast.makeText(
                this@MainActivity, R.string.file_picker_wierd, Toast.LENGTH_SHORT
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
                inputStream = this.contentResolver.openInputStream(uri)
            }
            // check if the file is a zip
            if (inputStream == null) {
                Toast.makeText(
                    this@MainActivity, R.string.file_picker_failure, Toast.LENGTH_SHORT
                ).show()
                callback?.onReceived(
                    destination,
                    uri,
                    IntentHelper.RESPONSE_ERROR
                )
                return@registerForActivityResult
            }
            run {
                outputStream = FileOutputStream(destination)
                Files.copy(inputStream, outputStream as FileOutputStream)
                if (MainApplication.forceDebugLogging) Timber.i("File saved at %s", destination)
                success = true
                callback?.onReceived(
                    destination,
                    uri,
                    IntentHelper.RESPONSE_FILE
                )
            }
        } catch (e: Exception) {
            Timber.e(e)
            Toast.makeText(
                this@MainActivity, R.string.file_picker_failure, Toast.LENGTH_SHORT
            ).show()
            callback?.onReceived(destination, uri, IntentHelper.RESPONSE_ERROR)
        } finally {
            Files.closeSilently(inputStream)
            Files.closeSilently(outputStream)
            if (!success && destination?.exists() == true && !destination!!.delete()) Timber.e("Failed to delete artifact!")
        }
    }

    init {
        moduleViewListBuilder.addNotification(NotificationType.INSTALL_FROM_STORAGE)
    }

    override fun onResume() {
        super.onResume()
        onMainActivityResume(this)
        // check that installed or online is selected depending on which recyclerview is visible
        if (moduleList!!.visibility == View.VISIBLE) {
            bottomNavigationView.selectedItemId = R.id.installed_menu_item
        } else {
            bottomNavigationView.selectedItemId = R.id.online_menu_item
        }
        // rescan modules
        if (!MainApplication.dirty) {
            instance!!.scanAsync()
        } else {
            MainApplication.dirty = false
            // same as onRefresh
            // call onrefresh
            swipeRefreshLayout!!.post { swipeRefreshLayout!!.isRefreshing = true }
            this.onRefresh()
        }

    }

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        initMode = true
        if (doSetupRestarting) {
            doSetupRestarting = false
        }
        onMainActivityCreate(this)
        super.onCreate(savedInstanceState)
        INSTANCE = this
        // check for pref_crashed and if so start crash handler
        val sharedPreferences = MainApplication.getPreferences("mmm")
        if (sharedPreferences?.getBoolean("pref_crashed", false) == true) {
            val intent = Intent(this, CrashHandler::class.java)
            startActivity(intent)
            finish()
            return
        }

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
                // use countly to track enabled repos
                val repoMap = HashMap<String, String>()
                repoMap["repos"] = enabledRepos.toString()
                if (MainApplication.analyticsAllowed()) Countly.sharedInstance().events()
                    .recordEvent(
                        "enabled_repos",
                        repoMap as Map<String, Any>?, 1
                    )
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
        // set navigation bar color based on surfacecolors
        window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        progressIndicator = findViewById(R.id.progress_bar)
        progressIndicator?.max = PRECISION
        progressIndicator?.min = 0
        progressIndicator?.setProgress(2, true)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh)
        val swipeRefreshLayout = swipeRefreshLayout!!
        swipeRefreshLayoutOrigStartOffset = swipeRefreshLayout.progressViewStartOffset
        swipeRefreshLayoutOrigEndOffset = swipeRefreshLayout.progressViewEndOffset
        swipeRefreshBlocker = Long.MAX_VALUE
        moduleList = findViewById(R.id.module_list)
        moduleListOnline = findViewById(R.id.module_list_online)
        searchTextInputEditText = findViewById(R.id.search_input)
        val textInputEditText = searchTextInputEditText!!
        val view = findViewById<View>(R.id.root_container)
        var startBottom = 0f
        var endBottom = 0f
        ViewCompat.setWindowInsetsAnimationCallback(view,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                // Override methods…
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    // Find an IME animation.
                    val imeAnimation = runningAnimations.find {
                        it.typeMask and WindowInsetsCompat.Type.ime() != 0
                    } ?: return insets
                    Timber.d("IME animation progress: %f", imeAnimation.interpolatedFraction)
                    // smoothly offset the view based on the interpolated fraction of the IME animation.
                    view.translationY =
                        (startBottom - endBottom) * (1 - imeAnimation.interpolatedFraction)

                    return insets
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    // Record the position of the view after the IME transition.
                    endBottom = view.bottom.toFloat()
                    Timber.d("IME animation start: %f", endBottom)
                    return bounds
                }

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    startBottom = view.bottom.toFloat()
                    Timber.d("IME animation prepare: %f", startBottom)
                }
            })
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
                if (MainApplication.analyticsAllowed()) Countly.sharedInstance().events()
                    .recordEvent("search", HashMap<String, String>().apply {
                        put("query", query)
                    } as Map<String, Any>?, 1)
                Thread {
                    if (moduleViewListBuilder.setQueryChange(query)) {
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "Query submit: %s on offline list",
                            query
                        )
                        Thread(
                            { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                            "Query update thread"
                        ).start()
                    }
                    // same for online list
                    if (moduleViewListBuilderOnline.setQueryChange(query)) {
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "Query submit: %s on online list",
                            query
                        )
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
                if (MainApplication.analyticsAllowed()) Countly.sharedInstance().events()
                    .recordEvent("search", HashMap<String, String>().apply {
                        put("query", query)
                    } as Map<String, Any>?, 1)
                Thread {
                    if (moduleViewListBuilder.setQueryChange(query)) {
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "Query submit: %s on offline list",
                            query
                        )
                        Thread(
                            { moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!) },
                            "Query update thread"
                        ).start()
                    }
                    // same for online list
                    if (moduleViewListBuilderOnline.setQueryChange(query)) {
                        if (MainApplication.forceDebugLogging) Timber.i(
                            "Query submit: %s on online list",
                            query
                        )
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
            val rebootDialog =
                MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.reboot).setItems(
                    arrayOf(
                        getString(R.string.reboot),
                        getString(R.string.reboot_recovery),
                        getString(R.string.reboot_bootloader),
                        getString(R.string.reboot_edl)
                    )
                ) { _: DialogInterface?, which: Int ->
                    when (which) {
                        0 -> RuntimeUtils.reboot(
                            this@MainActivity,
                            RuntimeUtils.RebootMode.REBOOT
                        )

                        1 -> RuntimeUtils.reboot(
                            this@MainActivity, RuntimeUtils.RebootMode.RECOVERY
                        )

                        2 -> RuntimeUtils.reboot(
                            this@MainActivity, RuntimeUtils.RebootMode.BOOTLOADER
                        )

                        3 -> RuntimeUtils.reboot(this@MainActivity, RuntimeUtils.RebootMode.EDL)
                    }
                }.setNegativeButton(R.string.cancel, null).create()
            rebootDialog.show()
        }
        // get background color and elevation of reboot fab
        moduleList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    textInputEditText.clearFocus()
                }
                // hide reboot fab on scroll by fading it out
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    rebootFab.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                rebootFab.visibility = View.GONE
                            }
                        })
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the search bar
                if (dy < 0) {
                    rebootFab.visibility = View.VISIBLE
                    rebootFab.animate().alpha(1f).setDuration(300).setListener(null)
                }
            }
        })
        // same for online
        moduleListOnline.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) textInputEditText.clearFocus()
                // hide search view when scrolling
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    textInputEditText.clearFocus()
                    // animate reboot fab out
                    rebootFab.animate().alpha(0f).setDuration(300)
                        .setListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                rebootFab.visibility = View.GONE
                            }
                        })
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                // if the user scrolled up, show the reboot fab
                if (dy < 0) {
                    rebootFab.visibility = View.VISIBLE
                    rebootFab.animate().alpha(1f).setDuration(300).setListener(null)
                }
            }
        })
        textInputEditText.imeOptions =
            EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN

        // on the bottom nav, there's a settings item. open the settings activity when it's clicked.
        bottomNavigationView = findViewById(R.id.bottom_navigation)
        bottomNavigationView.setOnItemSelectedListener { item: MenuItem ->
            when (item.itemId) {
                R.id.settings_menu_item -> {
                    // start settings activity so that when user presses back, they go back to main activity and on api34 they see a preview of the main activity. tell settings activity current active tab so that it can be selected when user goes back to main activity
                    val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                    when (bottomNavigationView.selectedItemId) {
                        R.id.online_menu_item -> intent.putExtra("activeTab", "online")
                        R.id.installed_menu_item -> intent.putExtra("activeTab", "installed")
                    }
                    startActivity(intent)
                }

                R.id.online_menu_item -> {
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
        // reset update module and update module count in main application
        MainApplication.INSTANCE!!.resetUpdateModule()
        tryGetMagiskPathAsync(object : InstallerInitializer.Callback {
            override fun onPathReceived(path: String?) {
                if (MainApplication.forceDebugLogging) Timber.i("Got magisk path: %s", path)
                if (peekMagiskVersion() < Constants.MAGISK_VER_CODE_INSTALL_COMMAND) {
                    if (!InstallerInitializer.isKsu) {
                        moduleViewListBuilder.addNotification(
                            NotificationType.MAGISK_OUTDATED
                        )
                    }
                }
                if (InstallerInitializer.isKsu) {
                    moduleViewListBuilder.addNotification(NotificationType.KSU_EXPERIMENTAL)
                }
                if (!MainApplication.isShowcaseMode) moduleViewListBuilder.addNotification(
                    NotificationType.INSTALL_FROM_STORAGE
                )
                instance!!.scan()
                instance!!.runAfterScan { moduleViewListBuilder.appendInstalledModules() }
                instance!!.runAfterScan { moduleViewListBuilderOnline.appendRemoteModules() }
                progressIndicator?.setProgress(10, true)
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
                    moduleViewListBuilder.addNotification(NotificationType.DEBUG)
                }

                NotificationType.NO_INTERNET.autoAdd(moduleViewListBuilderOnline)
                val progressIndicator = progressIndicator!!
                runOnUiThread {
                    progressIndicator.isIndeterminate = false
                    progressIndicator.setProgress(30, true)
                }
                // hide progress bar is repo-manager says we have no internet
                if (!RepoManager.getINSTANCE()!!.hasConnectivity()) {
                    if (MainApplication.forceDebugLogging) Timber.i("No connection, hiding progress")
                    runOnUiThread {
                        progressIndicator.visibility = View.GONE
                        progressIndicator.max = PRECISION
                    }
                }
                val context: Context = this@MainActivity
                if (runtimeUtils!!.waitInitialSetupFinished(context, this@MainActivity)) {
                    if (MainApplication.forceDebugLogging) Timber.d("waiting...")
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
                if (MainApplication.forceDebugLogging) Timber.i("Scanning for modules!")
                if (MainApplication.forceDebugLogging) Timber.i("Initialize Update")
                val max = instance!!.getUpdatableModuleCount()
                if (RepoManager.getINSTANCE()!!.customRepoManager != null && RepoManager.getINSTANCE()!!.customRepoManager!!.needUpdate()) {
                    Timber.w("Need update on create")
                } else if (RepoManager.getINSTANCE()!!.customRepoManager == null) {
                    Timber.w("CustomRepoManager is null")
                }
                // update compat metadata
                if (MainApplication.forceDebugLogging) Timber.i("Check Update Compat")
                appUpdateManager.checkUpdateCompat()
                if (MainApplication.forceDebugLogging) Timber.i("Check Update")
                // update repos. progress is from 30 to 80, so subtract 20 from max
                if (hasWebView()) {
                    val updateListener: SyncManager.UpdateListener =
                        object : SyncManager.UpdateListener {
                            override fun update(value: Int) {
                                Timber.i("Update progress: %d", value)
                                // progress is out of a hundred (Int) and starts at 30 once we've reached this point
                                runOnUiThread(if (max == 0) Runnable {
                                    progressIndicator.setProgress(
                                        80,
                                        true
                                    )
                                } else Runnable {
                                    progressIndicator.setProgress(
                                        30 + value,
                                        true
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
                            progressIndicator.setProgress(PRECISION, true)
                            progressIndicator.visibility = View.GONE
                        }
                        return
                    }
                    // Compatibility data still needs to be updated
                    val appUpdateManager = appUpdateManager
                    if (MainApplication.forceDebugLogging) Timber.i("Check App Update")
                    if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                        NotificationType.UPDATE_AVAILABLE
                    )
                    if (MainApplication.forceDebugLogging) Timber.i("Check Json Update")
                    if (max != 0) {
                        var current = 0
                        for (localModuleInfo in instance!!.modules.values) {
                            // if it has updateJson and FLAG_MM_REMOTE_MODULE is not set on flags, check for json update
                            // this is a dirty hack until we better store if it's a remote module
                            // the reasoning is that remote repos are considered "validated" while local modules are not
                            // for instance, a potential attacker could hijack a perfectly legitimate module and inject an updateJson with a malicious update - thereby bypassing any checks repos may have, without anyone noticing until it's too late
                            if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                                if (MainApplication.forceDebugLogging) Timber.i(localModuleInfo.id)
                                try {
                                    localModuleInfo.checkModuleUpdate()
                                } catch (e: Exception) {
                                    Timber.e(e)
                                }
                                current++
                                val currentTmp = current
                                // progress starts at 80 and goes to 99. each module should add a equal amount of progress to the bar, rounded up to the nearest integer
                                runOnUiThread {
                                    progressIndicator.setProgress(
                                        80 + (currentTmp / max.toFloat() * 20).roundToInt(),
                                        true
                                    )
                                    if (BuildConfig.DEBUG) {
                                        Timber.i(
                                            "Progress: %d",
                                            80 + (currentTmp / max.toFloat() * 20).roundToInt()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                runOnUiThread {
                    progressIndicator.isIndeterminate = true
                }
                if (MainApplication.forceDebugLogging) Timber.i("Apply")
                RepoManager.getINSTANCE()
                    ?.runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
                moduleViewListBuilder.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                moduleViewListBuilderOnline.applyTo(moduleListOnline, moduleViewAdapterOnline!!)
                moduleViewListBuilder.applyTo(moduleList, moduleViewAdapter!!)
                // if moduleViewListBuilderOnline has the upgradeable notification, show a badge on the online repo nav item
                if (MainApplication.INSTANCE!!.modulesHaveUpdates) {
                    if (MainApplication.forceDebugLogging) Timber.i("Applying badge")
                    Handler(Looper.getMainLooper()).post {
                        val badge = bottomNavigationView.getOrCreateBadge(R.id.online_menu_item)
                        badge.isVisible = true
                        badge.number = MainApplication.INSTANCE!!.updateModuleCount
                        badge.applyTheme(MainApplication.INSTANCE!!.theme)
                        if (MainApplication.forceDebugLogging) Timber.i("Badge applied")
                    }
                }
                maybeShowUpgrade()
                if (MainApplication.forceDebugLogging) Timber.i("Finished app opening state!")
                runOnUiThread {
                    progressIndicator.isIndeterminate = false
                    progressIndicator.setProgress(PRECISION, true)
                    progressIndicator.visibility = View.GONE
                }
            }
        }, true)
        ExternalHelper.INSTANCE.refreshHelper(this)
        initMode = false
        if (MainApplication.shouldShowFeedback() && !doSetupNowRunning) {
            // wait a bit before showing feedback
            Handler(Looper.getMainLooper()).postDelayed({
                showFeedback()
            }, 5000)
            if (MainApplication.forceDebugLogging) Timber.i("Should show feedback")
        } else {
            if (MainApplication.forceDebugLogging) Timber.i("Should not show feedback")
        }
    }

    private fun showFeedback() {
        if (MainApplication.analyticsAllowed()) Countly.sharedInstance().feedback()
            .getAvailableFeedbackWidgets { retrievedWidgets, error ->
                if (MainApplication.forceDebugLogging) Timber.i(
                    "Got feedback widgets: %s",
                    retrievedWidgets.size
                )
                if (error == null) {
                    if (retrievedWidgets.size > 0) {
                        val feedbackWidget = retrievedWidgets[0]
                        if (MainApplication.analyticsAllowed()) Countly.sharedInstance().feedback()
                            .presentFeedbackWidget(
                                feedbackWidget,
                                this@MainActivity,
                                "Close",
                                object : ModuleFeedback.FeedbackCallback {
                                    override fun onClosed() {
                                    }

                                    // maybe show a toast when the widget is closed
                                    override fun onFinished(error: String?) {
                                        // error handling here
                                        if (!error.isNullOrEmpty()) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Error: $error",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            Timber.e(error, "Feedback error")
                                        } else {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Feedback sent",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                })
                        // update last feedback time
                        MainApplication.getPreferences("mmm")?.edit()
                            ?.putLong("last_feedback", System.currentTimeMillis())?.apply()
                    }
                } else {
                    Timber.e(error, "Failed to get feedback widgets")
                }
            }
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

    override fun onRefresh() {
        if (swipeRefreshBlocker > System.currentTimeMillis() || initMode || progressIndicator == null || progressIndicator!!.visibility == View.VISIBLE || doSetupNowRunning) {
            swipeRefreshLayout!!.isRefreshing = false
            return  // Do not double scan
        }
        if (MainApplication.forceDebugLogging) Timber.i("Refresh")
        progressIndicator!!.visibility = View.VISIBLE
        // progress starts at 30 and ends at 80
        progressIndicator!!.setProgress(20, true)
        swipeRefreshBlocker = System.currentTimeMillis() + 5000L

        MainApplication.INSTANCE!!.repoModules.clear()
        // this.swipeRefreshLayout.setRefreshing(true); ??
        Thread({
            cleanDnsCache() // Allow DNS reload from network
            val max = instance!!.getUpdatableModuleCount()
            val updateListener: SyncManager.UpdateListener = object : SyncManager.UpdateListener {
                override fun update(value: Int) {
                    runOnUiThread(if (max == 0) Runnable {
                        progressIndicator!!.setProgress(
                            80, true
                        )
                    } else Runnable {
                        progressIndicator!!.setProgress(
                            // going from 30 to 80 as evenly as possible
                            30 + value,
                            true
                        )
                        if (BuildConfig.DEBUG) {
                            Timber.i(
                                "Progress: %d",
                                30 + value
                            )
                        }
                    })
                }
            }
            RepoManager.getINSTANCE()!!.update(updateListener)
            // rescan modules
            instance!!.scan()
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            if (!NotificationType.NO_INTERNET.shouldRemove()) {
                moduleViewListBuilderOnline.addNotification(NotificationType.NO_INTERNET)
            } else if (!NotificationType.REPO_UPDATE_FAILED.shouldRemove()) {
                moduleViewListBuilder.addNotification(NotificationType.REPO_UPDATE_FAILED)
            } else {
                // Compatibility data still needs to be updated
                val appUpdateManager = appUpdateManager
                if (MainApplication.forceDebugLogging) Timber.i("Check App Update")
                if (BuildConfig.ENABLE_AUTO_UPDATER && appUpdateManager.checkUpdate(true)) moduleViewListBuilder.addNotification(
                    NotificationType.UPDATE_AVAILABLE
                )
                if (MainApplication.forceDebugLogging) Timber.i("Check Json Update")
                if (max != 0) {
                    var current = 0
                    val totalLocalModules = instance!!.modules.size
                    for (localModuleInfo in instance!!.modules.values) {
                        if (localModuleInfo.updateJson != null && localModuleInfo.flags and ModuleInfo.FLAG_MM_REMOTE_MODULE == 0) {
                            if (MainApplication.forceDebugLogging) Timber.i(localModuleInfo.id)
                            try {
                                localModuleInfo.checkModuleUpdate()
                            } catch (e: Exception) {
                                Timber.e(e)
                            }
                            current++
                            val currentTmp = current
                            runOnUiThread {
                                progressIndicator!!.setProgress(
                                    // from 80 to 99, divided by total modules
                                    80 + (currentTmp / totalLocalModules.toFloat() * 20).roundToInt(),
                                    true
                                )
                            }
                        }
                    }
                }
            }
            if (MainApplication.forceDebugLogging) Timber.i("Apply")
            NotificationType.NEED_CAPTCHA_ANDROIDACY.autoAdd(moduleViewListBuilder)
            RepoManager.getINSTANCE()!!.updateEnabledStates()
            RepoManager.getINSTANCE()
                ?.runAfterUpdate { moduleViewListBuilder.appendInstalledModules() }
            RepoManager.getINSTANCE()
                ?.runAfterUpdate { moduleViewListBuilderOnline.appendRemoteModules() }
            moduleViewListBuilder.applyTo(moduleList!!, moduleViewAdapter!!)
            moduleViewListBuilder.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
            moduleViewListBuilderOnline.applyTo(moduleListOnline!!, moduleViewAdapterOnline!!)
            runOnUiThread {
                progressIndicator!!.setProgress(PRECISION, true)
                progressIndicator!!.visibility = View.GONE
                swipeRefreshLayout!!.isRefreshing = false
            }
        }, "Repo update thread").start()
    }

    fun maybeShowUpgrade() {
        if (AndroidacyRepoData.instance.memberLevel == null) {
            // wait for up to 10 seconds for AndroidacyRepoData to be initialized
            var i: Int
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == null) {
                if (MainApplication.forceDebugLogging) Timber.d("Member level is null, waiting for it to be initialized")
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
            if (AndroidacyRepoData.instance.memberLevel == null) {
                Timber.e("AndroidacyRepoData is enabled, but member level is null")
            }
            if (AndroidacyRepoData.instance.isEnabled && AndroidacyRepoData.instance.memberLevel == "Guest") {
                runtimeUtils!!.showUpgradeSnackbar(this, this)
            } else {
                if (AndroidacyRepoData.instance.memberLevel == null || !AndroidacyRepoData.instance.memberLevel.equals(
                        "Guest",
                        ignoreCase = true
                    )
                ) {
                    if (MainApplication.forceDebugLogging) Timber.i(
                        "AndroidacyRepoData is not Guest, not showing upgrade snackbar 1. Level: %s",
                        AndroidacyRepoData.instance.memberLevel
                    )
                } else {
                    if (MainApplication.forceDebugLogging) Timber.i("Unknown error, not showing upgrade snackbar 1")
                }
            }
        } else if (AndroidacyRepoData.instance.memberLevel.equals("Guest", ignoreCase = true)) {
            runtimeUtils!!.showUpgradeSnackbar(this, this)
        } else {
            if (!AndroidacyRepoData.instance.isEnabled) {
                if (MainApplication.forceDebugLogging) Timber.i("AndroidacyRepoData is disabled, not showing upgrade snackbar 2")
            } else if (AndroidacyRepoData.instance.memberLevel != "Guest") {
                if (MainApplication.forceDebugLogging) Timber.i(
                    "AndroidacyRepoData is not Guest, not showing upgrade snackbar 2. Level: %s",
                    AndroidacyRepoData.instance.memberLevel
                )
            } else {
                if (MainApplication.forceDebugLogging) Timber.i("Unknown error, not showing upgrade snackbar 2")
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

    override fun onDestroy() {
        super.onDestroy()
        INSTANCE = null
    }

    companion object {

        fun getAppCompatActivity(context: Context): AppCompatActivity {
            return context as AppCompatActivity
        }

        private const val PRECISION = 100

        @JvmField
        var doSetupNowRunning = true
        var doSetupRestarting = false
        var localModuleInfoList: List<LocalModuleInfo> = ArrayList()
        var onlineModuleInfoList: List<RepoModule> = ArrayList()
        var INSTANCE: MainActivity? = null
    }
}