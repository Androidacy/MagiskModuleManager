/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.LinearLayoutCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

// ProgressDialog is deprecated because it's an bad UX pattern, but sometimes we have no other choice...
enum class BudgetProgressDialog {
    ;

    companion object {
        fun build(context: Context, title: String?, message: String?): AlertDialog {
            val r = context.resources
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                20f,
                r.displayMetrics
            ).roundToInt()
            val v = LinearLayoutCompat(context)
            v.orientation = LinearLayoutCompat.HORIZONTAL
            val pb = ProgressBar(context)
            v.addView(
                pb,
                LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
            val t = TextView(context)
            t.gravity = Gravity.CENTER
            v.addView(
                t,
                LinearLayoutCompat.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    4f
                )
            )
            v.setPadding(padding, padding, padding, padding)
            t.text = message
            return MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setView(v)
                .setCancelable(false)
                .create()
        }

        fun build(context: Context, title: Int, message: String?): AlertDialog {
            return build(context, context.getString(title), message)
        }

        @JvmStatic
        fun build(context: Context, title: Int, message: Int): AlertDialog {
            return build(context, title, context.getString(message))
        }
    }
}