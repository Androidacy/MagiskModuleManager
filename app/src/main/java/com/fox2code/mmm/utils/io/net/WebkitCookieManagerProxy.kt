/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.utils.io.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import timber.log.Timber
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.CookieStore
import java.net.URI

class WebkitCookieManagerProxy internal constructor(
    @Suppress("UNUSED_PARAMETER") ignoredStore: CookieStore?,
    cookiePolicy: CookiePolicy?
) : CookieManager(null, cookiePolicy), CookieJar {
    private val webkitCookieManager: android.webkit.CookieManager =
        android.webkit.CookieManager.getInstance()

    constructor() : this(null, null)

    @Throws(IOException::class)
    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) {
        // make sure our args are valid

        // save our url once
        val url = uri.toString()

        // go over the headers
        for ((key, value) in responseHeaders) {
            // ignore headers which aren't cookie related
            if (!(key.equals("Set-Cookie2", ignoreCase = true) || key.equals(
                    "Set-Cookie",
                    ignoreCase = true
                ))
            ) continue
            for (headerValue in value) {
                webkitCookieManager.setCookie(url, headerValue)
            }
        }
    }

    @Throws(IOException::class)
    override fun get(
        uri: URI,
        requestHeaders: Map<String, List<String>>
    ): Map<String, List<String>> {
        // make sure our args are valid
        requireNotNull(requestHeaders.isEmpty()) { "Argument is null" }

        // save our url once
        val url = uri.toString()

        // prepare our response
        val res: MutableMap<String, List<String>> = HashMap()

        // get the cookie
        val cookie = webkitCookieManager.getCookie(url)

        // return it
        if (cookie != null) {
            res["Cookie"] = mutableListOf(cookie)
        }
        return res
    }

    override fun getCookieStore(): CookieStore {
        // we don't want anyone to work with this cookie store directly
        throw UnsupportedOperationException()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val generatedResponseHeaders = HashMap<String, List<String>>()
        val cookiesList = ArrayList<String>()
        for (c in cookies) {
            // toString correctly generates a normal cookie string
            cookiesList.add(c.toString())
        }
        generatedResponseHeaders["Set-Cookie"] = cookiesList
        try {
            put(url.toUri(), generatedResponseHeaders)
        } catch (e: IOException) {
            Timber.e(e, "Error adding cookies through okhttp")
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieArrayList = ArrayList<Cookie>()
        try {
            val cookieList = get(url.toUri(), HashMap())
            // Format here looks like: "Cookie":["cookie1=val1;cookie2=val2;"]
            for (ls in cookieList.values) {
                for (s in ls) {
                    val cookies =
                        s.split(";".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                    for (cookie in cookies) {
                        val c: Cookie = parse(url, cookie)
                        cookieArrayList.add(c)
                    }
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "error making cookie!")
        }
        return cookieArrayList
    }

    fun parse(url: HttpUrl, cookie: String): Cookie {
        val cookieParts = cookie.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val name = cookieParts[0]
        val value = cookieParts[1]
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain(url.host)
            .build()
    }
}