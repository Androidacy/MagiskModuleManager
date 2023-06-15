/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.markdown

import timber.log.Timber

enum class MarkdownUrlLinker {
    ;

    private class LinkifyTask(val start: Int, val end: Int) {
        companion object {
            val NULL = LinkifyTask(0, 0)
        }
    }

    companion object {
        @JvmStatic
        fun urlLinkify(url: String): String {
            var index = url.indexOf("https://")
            if (index == -1) return url
            val linkifyTasks = ArrayList<LinkifyTask>()
            var extra = 0
            while (index != -1) {
                var end = url.indexOf(' ', index)
                end = if (end == -1) url.indexOf('\n', index) else url.indexOf('\n', index)
                    .coerceAtMost(end)
                if (end == -1) end = url.length
                if (index == 0 || '\n' == url[index - 1] || ' ' == url[index - 1]) {
                    val endDomain = url.indexOf('/', index + 9)
                    val endCh = url[end - 1]
                    if (endDomain != -1 && endDomain < end && endCh != '>' && endCh != ')' && endCh != ']') {
                        linkifyTasks.add(LinkifyTask(index, end))
                        extra += end - index + 4
                        Timber.d("Linkify url: %s", url.substring(end))
                    }
                }
                index = url.indexOf("https://", end)
            }
            if (linkifyTasks.isEmpty()) return url
            var prev = LinkifyTask.NULL
            val stringBuilder = StringBuilder(url.length + extra)
            for (linkifyTask in linkifyTasks) {
                stringBuilder.append(url, prev.end, linkifyTask.start).append('[')
                    .append(url, linkifyTask.start, linkifyTask.end).append("](")
                    .append(url, linkifyTask.start, linkifyTask.end).append(')')
                prev = linkifyTask
            }
            if (prev.end != url.length) stringBuilder.append(url, prev.end, url.length)
            Timber.i("Added Markdown link to " + linkifyTasks.size + " urls")
            return stringBuilder.toString()
        }
    }
}