/*
 * SPDX-FileCopyrightText: 2019 CypherOS
 * SPDX-FileCopyrightText: 2014-2020 Paranoid Android
 * SPDX-FileCopyrightText: 2023-2026 The LineageOS Project
 * SPDX-FileCopyrightText: 2023 Yet Another AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.AmbientDisplayConfiguration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.view.Display
import android.view.Surface
import android.view.View
import com.android.systemui.plugins.OverlayPlugin
import com.android.systemui.plugins.annotations.Requires

@Requires(target = OverlayPlugin::class, version = OverlayPlugin.VERSION)
class AlertSliderPlugin : OverlayPlugin {
    private lateinit var ambientConfig: AmbientDisplayConfiguration
    private lateinit var pluginContext: Context
    private lateinit var handler: NotificationHandler
    private val dialogLock = Any()

    private data class NotificationInfo(
        val position: Int,
        val mode: Int,
        val invertColors: Boolean,
        val dialogLocation: Int,
    )

    private fun getDialogLocation(): Int {
        try {
            val v = android.provider.Settings.Secure.getInt(
                pluginContext.contentResolver,
                KeyHandler.DIALOG_LOCATION,
                -999,
            )
            if (v != -999) return v
        } catch (e: Throwable) {
            // Ignore
        }
        val deContext = try {
            pluginContext.createDeviceProtectedStorageContext()
        } catch (e2: Throwable) {
            pluginContext
        }
        val prefContexts = listOf(pluginContext, deContext)
        for (ctx in prefContexts) {
            try {
                val prefs = ctx.getSharedPreferences(
                    pluginContext.packageName + "_preferences",
                    Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
                )
                val v = prefs.getString(KeyHandler.DIALOG_LOCATION, null)?.toIntOrNull()
                    ?: if (prefs.contains(KeyHandler.DIALOG_LOCATION)) prefs.getInt(KeyHandler.DIALOG_LOCATION, -999) else -999
                if (v != -999) return v
            } catch (e: Throwable) {
                // Ignore
            }
        }
        return KeyHandler.DIALOG_LOCATION_SIDE
    }

    private fun getInvertColors(): Boolean {
        try {
            val v = android.provider.Settings.Secure.getInt(
                pluginContext.contentResolver,
                KeyHandler.INVERT_COLORS,
                -999,
            )
            if (v != -999) return v == 1
        } catch (e: Throwable) {
            // Ignore
        }
        val deContext = try {
            pluginContext.createDeviceProtectedStorageContext()
        } catch (e2: Throwable) {
            pluginContext
        }
        val prefContexts = listOf(pluginContext, deContext)
        for (ctx in prefContexts) {
            try {
                val prefs = ctx.getSharedPreferences(
                    pluginContext.packageName + "_preferences",
                    Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
                )
                if (prefs.contains(KeyHandler.INVERT_COLORS)) {
                    return try {
                        prefs.getBoolean(KeyHandler.INVERT_COLORS, false)
                    } catch (e: Throwable) {
                        prefs.getInt(KeyHandler.INVERT_COLORS, 0) == 1
                    }
                }
            } catch (e: Throwable) {
                // Ignore
            }
        }
        return false
    }

    private val updateReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        synchronized(dialogLock) { handler.sendEmptyMessage(MSG_DIALOG_RECREATE) }
                    }
                    KeyHandler.CHANGED_ACTION -> {
                        synchronized(dialogLock) {
                            val ringer = intent.getIntExtra("mode", NONE)
                            if (ringer != NONE) {
                                val invert = intent.getBooleanExtra("invertColors", getInvertColors())
                                val location =
                                    intent.getIntExtra(
                                        KeyHandler.DIALOG_LOCATION,
                                        getDialogLocation(),
                                    )

                                handler
                                    .obtainMessage(
                                        MSG_DIALOG_UPDATE,
                                        NotificationInfo(
                                            intent.getIntExtra("position", KeyHandler.POSITION_BOTTOM),
                                            ringer,
                                            invert,
                                            location,
                                        ),
                                    )
                                    .sendToTarget()
                                handler.sendEmptyMessage(MSG_DIALOG_SHOW)
                            } else {
                                handler.sendMessage(
                                    handler.obtainMessage(MSG_SETTINGS_CHANGED, intent)
                                )
                            }
                        }
                    }
                }
            }
        }

    override fun onCreate(context: Context, plugin: Context) {
        ambientConfig = AmbientDisplayConfiguration(context)
        pluginContext = plugin
        handler = NotificationHandler(plugin, context)

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(KeyHandler.CHANGED_ACTION)
            }
        plugin.registerReceiver(updateReceiver, filter, Context.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        try {
            pluginContext.unregisterReceiver(updateReceiver)
        } catch (e: Throwable) {
            // Ignore if already unregistered
        }
        handler.destroy()
    }

    override fun setup(statusBar: View?, navBar: View?) {}

    private inner class NotificationHandler(
        var context: Context,
        private var sysuiContext: Context,
    ) : Handler(Looper.getMainLooper()) {
        private var sideDialog = AlertSliderDialog(context, sysuiContext)
        private var islandDialog = AlertSliderIslandDialog(context, sysuiContext).apply {
            onDismissRequested = { showing = false }
        }

        private fun getSafeRotation(): Int {
            val dm = (sysuiContext.getSystemService(DisplayManager::class.java)
                ?: context.getSystemService(DisplayManager::class.java))
            return dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        }

        private var currDensity = context.resources.configuration.densityDpi
        private var currRotation = getSafeRotation()
        private var currSmallestWidth = context.resources.configuration.smallestScreenWidthDp
        private var currUIMode = context.resources.configuration.uiMode
        private var lastInfo: NotificationInfo? = null
        private var showing = false
            set(value) {
                synchronized(dialogLock) {
                    if (field != value) {
                        // Remove pending messages
                        removeMessages(MSG_DIALOG_SHOW)
                        removeMessages(MSG_DIALOG_DISMISS)
                        removeMessages(MSG_DIALOG_RESET)

                        // Show/hide dialog
                        if (value) {
                            handleResetTimeout()
                            launchDozePulse()
                            if (lastInfo?.dialogLocation == KeyHandler.DIALOG_LOCATION_ISLAND) {
                                if (sideDialog.isShowing) sideDialog.dismiss()
                                islandDialog.show()
                            } else {
                                if (islandDialog.isShowing) islandDialog.dismiss()
                                sideDialog.show()
                            }
                        } else {
                            if (sideDialog.isShowing) sideDialog.dismiss()
                            if (islandDialog.isShowing) islandDialog.dismiss()
                        }
                    }

                    field = value
                }
            }

        override fun handleMessage(msg: Message) =
            when (msg.what) {
                MSG_DIALOG_SHOW -> handleShow()
                MSG_DIALOG_DISMISS -> handleDismiss()
                MSG_DIALOG_RESET -> handleResetTimeout()
                MSG_DIALOG_UPDATE -> handleUpdate(msg.obj as NotificationInfo)
                MSG_DIALOG_RECREATE -> handleRecreate()
                MSG_SETTINGS_CHANGED -> handleSettingsChanged(msg.obj as? Intent)
                else -> {}
            }

        private fun handleShow() {
            showing = true
        }

        private fun handleDismiss() {
            showing = false
        }

        private fun handleResetTimeout() {
            synchronized(dialogLock) {
                removeMessages(MSG_DIALOG_DISMISS)
                sendMessageDelayed(
                    handler.obtainMessage(MSG_DIALOG_DISMISS, MSG_DIALOG_RESET, 0),
                    DIALOG_TIMEOUT,
                )
            }
        }

        private fun handleSettingsChanged(intent: Intent?) {
            synchronized(dialogLock) {
                val newLocation = if (intent?.hasExtra(KeyHandler.DIALOG_LOCATION) == true) {
                    intent.getIntExtra(KeyHandler.DIALOG_LOCATION, getDialogLocation())
                } else {
                    getDialogLocation()
                }

                val newInvert = if (intent?.hasExtra(KeyHandler.INVERT_COLORS) == true) {
                    intent.getBooleanExtra(KeyHandler.INVERT_COLORS, getInvertColors())
                } else {
                    getInvertColors()
                }

                lastInfo?.let {
                    if (it.dialogLocation != newLocation || it.invertColors != newInvert) {
                        val wasShowing = showing
                        lastInfo = it.copy(dialogLocation = newLocation, invertColors = newInvert)
                        if (wasShowing) {
                            if (newLocation == KeyHandler.DIALOG_LOCATION_ISLAND) {
                                if (sideDialog.isShowing) sideDialog.dismiss()
                                islandDialog.setState(it.position, it.mode, newInvert)
                                if (!islandDialog.isShowing) islandDialog.show()
                            } else {
                                if (islandDialog.isShowing) islandDialog.dismiss()
                                sideDialog.setState(it.position, it.mode, newInvert)
                                if (!sideDialog.isShowing) sideDialog.show()
                            }
                        }
                    }
                }
            }
        }

        private fun handleUpdate(info: NotificationInfo) {
            synchronized(dialogLock) {
                // The tri-state key position can be re-announced (same position,
                // same mode) well after the previous dialog has already
                // auto-dismissed - e.g. on pickup/tilt, several seconds apart,
                // with no real slider movement in between. Suppress any repeat
                // of the exact same state unconditionally, and only clear the
                // suppression once a genuinely different state is delivered.
                if (info == lastInfo) {
                    return
                }

                val oldLocation = lastInfo?.dialogLocation
                lastInfo = info
                handleResetTimeout()
                launchDozePulse()

                if (info.dialogLocation == KeyHandler.DIALOG_LOCATION_ISLAND) {
                    if (oldLocation == KeyHandler.DIALOG_LOCATION_SIDE && sideDialog.isShowing) {
                        sideDialog.dismiss()
                    }
                    islandDialog.setState(info.position, info.mode, info.invertColors)
                    if (showing && !islandDialog.isShowing) {
                        islandDialog.show()
                    }
                } else {
                    if (oldLocation == KeyHandler.DIALOG_LOCATION_ISLAND && islandDialog.isShowing) {
                        islandDialog.dismiss()
                    }
                    sideDialog.setState(info.position, info.mode, info.invertColors)
                    if (showing && !sideDialog.isShowing) {
                        sideDialog.show()
                    }
                }

                if (!showing) {
                    showing = true
                }
            }
        }

        private fun handleRecreate() {
            val config = context.resources.configuration
            val density = config.densityDpi
            val rotation = getSafeRotation()
            val smallestWidth = config.smallestScreenWidthDp
            val uiMode = config.uiMode

            val densityChanged = density != currDensity
            val layoutChanged = smallestWidth != currSmallestWidth
            val rotationChanged = rotation != currRotation
            val themeChanged = uiMode != currUIMode
            if (densityChanged || layoutChanged || rotationChanged || themeChanged) {
                val wasShowing = showing

                showing = false
                try {
                    islandDialog.dismissImmediate()
                } catch (e: Throwable) {
                    // Ignore
                }
                sideDialog = AlertSliderDialog(context, sysuiContext)
                islandDialog = AlertSliderIslandDialog(context, sysuiContext).apply {
                    onDismissRequested = { showing = false }
                }
                lastInfo?.let {
                    if (it.dialogLocation == KeyHandler.DIALOG_LOCATION_ISLAND) {
                        islandDialog.setState(it.position, it.mode, it.invertColors)
                    } else {
                        sideDialog.setState(it.position, it.mode, it.invertColors)
                    }
                }

                if (wasShowing) {
                    showing = true
                }

                currDensity = density
                currRotation = rotation
                currSmallestWidth = smallestWidth
                currUIMode = uiMode
            }
        }

        private fun launchDozePulse() {
            if (ambientConfig.pulseOnNotificationEnabled(UserHandle.USER_CURRENT)) {
                context.sendBroadcastAsUser(Intent(DOZE_INTENT), UserHandle.CURRENT)
            }
        }

        fun destroy() {
            removeCallbacksAndMessages(null)
            try {
                if (sideDialog.isShowing) sideDialog.dismiss()
            } catch (e: Throwable) {
                // Ignore
            }
            try {
                islandDialog.dismissImmediate()
            } catch (e: Throwable) {
                // Ignore
            }
        }
    }

    companion object {
        private const val TAG = "AlertSliderPlugin"

        // Intent
        private const val DOZE_INTENT = "com.android.systemui.doze.pulse"

        // Handler
        private const val MSG_DIALOG_SHOW = 1
        private const val MSG_DIALOG_DISMISS = 2
        private const val MSG_DIALOG_RESET = 3
        private const val MSG_DIALOG_UPDATE = 4
        private const val MSG_DIALOG_RECREATE = 5
        private const val MSG_SETTINGS_CHANGED = 6
        private const val DIALOG_TIMEOUT = 1500L

        // Dialog
        private const val DISABLED = 0
        private const val SHOW_LEFT = 1
        private const val SHOW_RIGHT = 2

        // Ringer mode
        private const val NONE = -1
    }
}
