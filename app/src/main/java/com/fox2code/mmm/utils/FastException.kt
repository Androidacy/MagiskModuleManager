package com.fox2code.mmm.utils

class FastException private constructor() : RuntimeException() {
    @Synchronized
    override fun fillInStackTrace(): Throwable {
        return this
    }

    companion object {
        val INSTANCE = FastException()
    }
}