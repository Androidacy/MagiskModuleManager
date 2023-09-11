/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

class LongClickablePreference : Preference {
    var onPreferenceLongClickListener: OnPreferenceLongClickListener? = null

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context) : super(context)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.setOnLongClickListener { _: View? -> performLongClick() }
    }

    private fun performLongClick(): Boolean {
        if (!this.isEnabled || !this.isSelectable) {
            return false
        }
        return if (onPreferenceLongClickListener != null) {
            onPreferenceLongClickListener!!.onPreferenceLongClick(this)
        } else false
    }

    fun interface OnPreferenceLongClickListener {
        /**
         * Called when a preference has been clicked.
         *
         * @param preference The preference that was clicked
         * @return `true` if the click was handled
         */
        fun onPreferenceLongClick(preference: Preference): Boolean
    }
}