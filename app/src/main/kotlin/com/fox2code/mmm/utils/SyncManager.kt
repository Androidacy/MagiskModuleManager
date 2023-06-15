/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */
package com.fox2code.mmm.utils

/**
 * Manager that want both to be thread safe and not to worry about thread safety
 * [.scan] and [.update] can be called from multiple
 * thread at the same time, [.scanInternal] will only be
 * called from one thread at a time only.
 */
abstract class SyncManager {
    protected val syncLock = Any()
    private var syncing = false
    private var lastSync: Long = 0
    fun scanAsync() {
        if (!syncing) {
            Thread({ this.scan() }, "Scan Thread").start()
        }
    }

    fun scan() {
        update(null)
    }

    // MultiThread friendly method
    @Suppress("NAME_SHADOWING")
    fun update(updateListener: UpdateListener?) {
        var updateListener = updateListener
        if (updateListener == null) updateListener = NO_OP
        if (!syncing) {
            // Do scan
            synchronized(syncLock) {
                if (System.currentTimeMillis() < lastSync + 50L) return  // Skip sync if it was synced too recently
                syncing = true
                try {
                    scanInternal(updateListener)
                } finally {
                    lastSync = System.currentTimeMillis()
                    syncing = false
                }
            }
        } else {
            // Wait for current scan
            synchronized(syncLock) { Thread.yield() }
        }
    }

    // Pause execution until the scan is completed if one is currently running
    fun afterScan() {
        if (syncing) synchronized(syncLock) { Thread.yield() }
    }

    fun runAfterScan(runnable: Runnable) {
        synchronized(syncLock) { runnable.run() }
    }

    fun afterUpdate() {
        if (syncing) synchronized(syncLock) { Thread.yield() }
    }

    fun runAfterUpdate(runnable: Runnable) {
        synchronized(syncLock) { runnable.run() }
    }

    // This method can't be called twice at the same time.
    protected abstract fun scanInternal(updateListener: UpdateListener)
    interface UpdateListener {
        fun update(value: Double)
    }

    companion object {
        private val NO_OP: UpdateListener = object : UpdateListener {
            override fun update(value: Double) {

            }
        }
    }
}