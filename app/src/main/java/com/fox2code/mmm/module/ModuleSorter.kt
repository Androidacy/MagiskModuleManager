package com.fox2code.mmm.module

import androidx.annotation.DrawableRes
import com.fox2code.mmm.R

enum class ModuleSorter(@field:DrawableRes @JvmField val icon: Int) : Comparator<ModuleHolder> {
    UPDATE(R.drawable.ic_baseline_update_24) {
        override operator fun next(): ModuleSorter {
            return ALPHA
        }
    },
    ALPHA(R.drawable.ic_baseline_sort_by_alpha_24) {
        override fun compare(holder1: ModuleHolder, holder2: ModuleHolder): Int {
            val type1 = holder1.type
            val type2 = holder2.type
            if (type1 === type2 && type1 === ModuleHolder.Type.INSTALLABLE) {
                var compare = holder1.filterLevel.compareTo(holder2.filterLevel)
                if (compare != 0) return compare
                compare = holder1.mainModuleNameLowercase
                    .compareTo(holder2.mainModuleNameLowercase)
                if (compare != 0) return compare
            }
            return super.compare(holder1, holder2)
        }

        override operator fun next(): ModuleSorter {
            return UPDATE
        }
    };

    override fun compare(holder1: ModuleHolder, holder2: ModuleHolder): Int {
        return holder1.compareTo(holder2)
    }

    abstract operator fun next(): ModuleSorter?
}