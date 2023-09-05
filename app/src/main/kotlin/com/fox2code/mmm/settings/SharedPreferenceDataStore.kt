/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.settings

import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import com.fox2code.mmm.BuildConfig
import timber.log.Timber

class SharedPreferenceDataStore(sharedPreferences: SharedPreferences) : PreferenceDataStore() {
    private val mSharedPreferences: SharedPreferences

    init {
        if (BuildConfig.DEBUG) Timber.d("SharedPreferenceDataStore: %s", sharedPreferences)
        mSharedPreferences = sharedPreferences
    }

    val sharedPreferences: SharedPreferences
        get() {
            if (BuildConfig.DEBUG) Timber.d("getSharedPreferences: %s", mSharedPreferences)
            return mSharedPreferences
        }

    override fun putString(key: String, value: String?) {
        mSharedPreferences.edit().putString(key, value).apply()
    }

    override fun putStringSet(key: String, values: Set<String>?) {
        mSharedPreferences.edit().putStringSet(key, values).apply()
    }

    override fun putInt(key: String, value: Int) {
        mSharedPreferences.edit().putInt(key, value).apply()
    }

    override fun putLong(key: String, value: Long) {
        mSharedPreferences.edit().putLong(key, value).apply()
    }

    override fun putFloat(key: String, value: Float) {
        mSharedPreferences.edit().putFloat(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        mSharedPreferences.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String, defValue: String?): String? {
        return mSharedPreferences.getString(key, defValue)
    }

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        return mSharedPreferences.getStringSet(key, defValues)
    }

    override fun getInt(key: String, defValue: Int): Int {
        return mSharedPreferences.getInt(key, defValue)
    }

    override fun getLong(key: String, defValue: Long): Long {
        return mSharedPreferences.getLong(key, defValue)
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return mSharedPreferences.getFloat(key, defValue)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return mSharedPreferences.getBoolean(key, defValue)
    }
}