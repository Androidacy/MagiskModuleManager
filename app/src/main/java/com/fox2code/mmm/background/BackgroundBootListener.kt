package com.fox2code.mmm.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.utils.io.net.Http

class BackgroundBootListener : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BOOT_COMPLETED != intent.action) return
        if (!MainApplication.isBackgroundUpdateCheckEnabled()) return
        if (!Http.hasConnectivity(context)) return
        // clear boot shared prefs
        MainApplication.getBootSharedPreferences().edit().clear().apply()
        synchronized(BackgroundUpdateChecker.lock) {
            Thread {
                BackgroundUpdateChecker.onMainActivityCreate(context)
                BackgroundUpdateChecker.doCheck(context)
            }.start()
        }
    }

    companion object {
        private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    }
}