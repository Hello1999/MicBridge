package com.jack.micbridge.service

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.os.Build
import androidx.annotation.RequiresApi

/** Watches Android hotspot downstream interfaces when the public API is available. */
internal object TetheringChangeMonitor {
    interface Monitor : AutoCloseable {
        fun interfaceNames(): Set<String>
    }

    fun start(context: Context, onChanged: () -> Unit): Monitor? {
        if (Build.VERSION.SDK_INT < 36) return null
        return Api36Monitor.start(context, onChanged)
    }

    @RequiresApi(36)
    private class Api36Monitor(
        private val manager: TetheringManager,
        private val callback: TetheringManager.TetheringEventCallback,
    ) : Monitor {
        @Volatile
        private var names: Set<String> = emptySet()

        override fun interfaceNames(): Set<String> = names

        override fun close() {
            runCatching { manager.unregisterTetheringEventCallback(callback) }
            names = emptySet()
        }

        companion object {
            fun start(context: Context, onChanged: () -> Unit): Monitor? = runCatching {
                val manager = context.getSystemService(TetheringManager::class.java)
                    ?: return@runCatching null
                lateinit var monitor: Api36Monitor
                val callback = object : TetheringManager.TetheringEventCallback {
                    override fun onTetheredInterfacesChanged(
                        interfaces: Set<TetheringInterface>,
                    ) {
                        monitor.names = interfaces
                            .filter { it.getType() == TetheringManager.TETHERING_WIFI }
                            .map { it.getInterface() }
                            .toSet()
                        onChanged()
                    }
                }
                monitor = Api36Monitor(manager, callback)
                manager.registerTetheringEventCallback(context.mainExecutor, callback)
                monitor
            }.getOrNull()
        }
    }
}
