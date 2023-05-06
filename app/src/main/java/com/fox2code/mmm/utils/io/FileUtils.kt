package com.fox2code.mmm.utils.io

import android.content.Context
import com.fox2code.mmm.MainApplication
import org.apache.commons.io.FileUtils
import org.chromium.net.CronetEngine
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.URL

class FileUtils {
    private var urlFactoryInstalled = false

    fun ensureCacheDirs() {
        try {
            FileUtils.forceMkdir(File((MainApplication.getINSTANCE().dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/wasm").replace("//".toRegex(), "/")))
            FileUtils.forceMkdir(File((MainApplication.getINSTANCE().dataDir.toString() + "/cache/WebView/Default/HTTP Cache/Code Cache/js").replace("//".toRegex(), "/")))
            FileUtils.forceMkdir(File((MainApplication.getINSTANCE().dataDir.toString() + "/cache/cronet").replace("//".toRegex(), "/")))
        } catch (e: IOException) {
            Timber.e("Could not create cache dirs")
        }
    }

    fun ensureURLHandler(context: Context?) {
        if (!urlFactoryInstalled) {
            try {
                URL.setURLStreamHandlerFactory(CronetEngine.Builder(context).build().createURLStreamHandlerFactory())
                urlFactoryInstalled = true
            } catch (ignored: Error) {
                // Ignore
            }
        }
    }
}