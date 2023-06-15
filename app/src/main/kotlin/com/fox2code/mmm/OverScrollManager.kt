/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm

class OverScrollManager {
    interface OverScrollHelper {
        val overScrollInsetTop: Int
        val overScrollInsetBottom: Int
    }
}