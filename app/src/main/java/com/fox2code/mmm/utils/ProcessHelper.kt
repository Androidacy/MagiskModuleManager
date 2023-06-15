/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fox2code.mmm.MainActivity
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.exitProcess

enum class ProcessHelper {
    ;

    companion object {
        private val sPendingIntentId = ThreadLocalRandom.current().nextInt(100, 1000000 + 1)
        @JvmStatic
        fun restartApplicationProcess(context: Context) {
            val mStartActivity = Intent(context, MainActivity::class.java)
            mStartActivity.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            val mPendingIntent = PendingIntent.getActivity(
                context, sPendingIntentId,
                mStartActivity, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val mgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            mgr[AlarmManager.RTC, System.currentTimeMillis() + 100] = mPendingIntent
            exitProcess(0) // Exit app process
        }
    }
}