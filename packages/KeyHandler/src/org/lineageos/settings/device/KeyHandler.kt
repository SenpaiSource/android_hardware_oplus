/*
 * Copyright (C) 2021-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioSystem
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler
import java.io.File
import java.util.concurrent.Executors

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val audioManager = context.getSystemService(AudioManager::class.java)!!
    private val cameraManager = context.getSystemService(CameraManager::class.java)!!
    private val notificationManager = context.getSystemService(NotificationManager::class.java)!!
    private val vibrator = context.getSystemService(Vibrator::class.java)!!

    private val packageContext: Context
        get() =
            try {
                val pkg = KeyHandler::class.java.getPackage()!!.name
                val ctx = context.createPackageContext(pkg, 0)
                try {
                    ctx.createDeviceProtectedStorageContext()
                } catch (e: Throwable) {
                    ctx
                }
            } catch (e: Exception) {
                context
            }

    private val executorService = Executors.newSingleThreadExecutor()

    private var wasMuted = false
    private var currentPosition = POSITION_BOTTOM

    private var muteMedia = false
    private var showDialog = true
    private var invertColors = false
    private var dialogLocation = DIALOG_LOCATION_SIDE
    private var islandStyle = ISLAND_STYLE_STANDARD
    private var topPosition = 0
    private var middlePosition = 1
    private var bottomPosition = 2

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.STREAM_MUTE_CHANGED_ACTION -> {
                        val stream = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1)
                        val state =
                            intent.getBooleanExtra(AudioManager.EXTRA_STREAM_VOLUME_MUTED, false)
                        if (stream == AudioSystem.STREAM_MUSIC && !state) {
                            wasMuted = false
                        }
                    }

                    Intent.ACTION_BOOT_COMPLETED -> {
                        refreshSettings()
                        populateKeyState(true)
                    }

                    CYCLE_ACTION -> cycleNextMode()

                    CHANGED_ACTION -> {
                        updateSettingsFromIntent(intent)
                        populateKeyState(true)
                    }
                }
            }
        }

    init {
        refreshSettings()
        populateKeyState(true)
        context.registerReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION)
                addAction(Intent.ACTION_BOOT_COMPLETED)
                addAction(CYCLE_ACTION)
                addAction(CHANGED_ACTION)
            },
            Context.RECEIVER_EXPORTED,
        )
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event
        }

        val deviceName = event.device.name

        if (deviceName != "oplus,hall_tri_state_key" && deviceName != "oplus,tri-state-key") {
            return event
        }

        populateKeyState(false)

        return null
    }

    private fun populateKeyState(firstRun: Boolean) {
        try {
            val file = File("/proc/tristatekey/tri_state")
            if (file.exists()) {
                val newPosition = when (file.readText().trim()) {
                    "1" -> POSITION_TOP
                    "2" -> POSITION_MIDDLE
                    "3" -> POSITION_BOTTOM
                    else -> return
                }
                if (!firstRun && newPosition == currentPosition) {
                    return
                }
                handleMode(newPosition, firstRun)
            }
        } catch (e: Throwable) {
            // Ignore if sysfs/proc node is inaccessible
        }
    }

    private fun cycleNextMode() {
        val nextPosition = when (currentPosition) {
            POSITION_BOTTOM -> POSITION_MIDDLE
            POSITION_MIDDLE -> POSITION_TOP
            POSITION_TOP -> POSITION_BOTTOM
            else -> POSITION_BOTTOM
        }
        currentPosition = nextPosition
        handleMode(nextPosition, false)
    }

    private fun vibrateIfNeeded(mode: Int) {
        when (mode) {
            AudioManager.RINGER_MODE_VIBRATE ->
                vibrator.vibrate(MODE_VIBRATION_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
            AudioManager.RINGER_MODE_NORMAL ->
                vibrator.vibrate(MODE_NORMAL_EFFECT, HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES)
        }
    }

    private fun updateSettingsFromIntent(intent: Intent) {
        if (intent.hasExtra(SHOW_DIALOG)) {
            showDialog = intent.getBooleanExtra(SHOW_DIALOG, showDialog)
        }
        if (intent.hasExtra(DIALOG_LOCATION)) {
            dialogLocation = intent.getIntExtra(DIALOG_LOCATION, dialogLocation)
        }
        if (intent.hasExtra(INVERT_COLORS)) {
            invertColors = intent.getBooleanExtra(INVERT_COLORS, invertColors)
        }
        if (intent.hasExtra(MUTE_MEDIA_WITH_SILENT)) {
            muteMedia = intent.getBooleanExtra(MUTE_MEDIA_WITH_SILENT, muteMedia)
        }
        if (intent.hasExtra(ALERT_SLIDER_TOP_KEY)) {
            topPosition = intent.getIntExtra(ALERT_SLIDER_TOP_KEY, topPosition)
        }
        if (intent.hasExtra(ALERT_SLIDER_MIDDLE_KEY)) {
            middlePosition = intent.getIntExtra(ALERT_SLIDER_MIDDLE_KEY, middlePosition)
        }
        if (intent.hasExtra(ALERT_SLIDER_BOTTOM_KEY)) {
            bottomPosition = intent.getIntExtra(ALERT_SLIDER_BOTTOM_KEY, bottomPosition)
        }
        if (intent.hasExtra(ALERT_SLIDER_ISLAND_STYLE)) {
            islandStyle = intent.getIntExtra(ALERT_SLIDER_ISLAND_STYLE, islandStyle)
        }
    }

    private fun refreshSettings() {
        muteMedia = getSettingBoolean(MUTE_MEDIA_WITH_SILENT, false)
        showDialog = getSettingBoolean(SHOW_DIALOG, true)
        invertColors = getSettingBoolean(INVERT_COLORS, false)
        dialogLocation = getSettingInt(DIALOG_LOCATION, DIALOG_LOCATION_SIDE)
        islandStyle = getSettingInt(ALERT_SLIDER_ISLAND_STYLE, ISLAND_STYLE_STANDARD)
        topPosition = getSettingInt(ALERT_SLIDER_TOP_KEY, 0)
        middlePosition = getSettingInt(ALERT_SLIDER_MIDDLE_KEY, 1)
        bottomPosition = getSettingInt(ALERT_SLIDER_BOTTOM_KEY, 2)
    }

    private fun getSettingBoolean(key: String, def: Boolean): Boolean {
        // 1. Settings.Secure (Works reliably across processes on AOSP/VoltageOS)
        try {
            val v = Settings.Secure.getInt(context.contentResolver, key, -999)
            if (v != -999) return v == 1
        } catch (e: Throwable) {
            // Ignore
        }

        // 2. LineageSettings via reflection
        try {
            val clazz = Class.forName("lineageos.providers.LineageSettings\$System")
            val method = clazz.getMethod(
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val v = method.invoke(null, context.contentResolver, key, -999) as? Int
            if (v != null && v != -999) return v == 1
        } catch (e: Throwable) {
            // Ignore
        }

        // 3. SharedPreferences fallback
        return readPrefBoolean(key, def)
    }

    private fun readPrefBoolean(key: String, def: Boolean): Boolean {
        val prefContexts = mutableListOf(packageContext)
        try {
            prefContexts.add(packageContext.createDeviceProtectedStorageContext())
        } catch (e: Throwable) {
            // Ignore
        }

        for (ctx in prefContexts) {
            try {
                val sp = ctx.getSharedPreferences(
                    packageContext.packageName + "_preferences",
                    Context.MODE_PRIVATE,
                )
                if (sp.contains(key)) {
                    return sp.getBoolean(key, def)
                }
            } catch (e: Throwable) {
                // Ignore
            }
        }
        return def
    }

    private fun getSettingInt(key: String, def: Int): Int {
        // 1. Settings.Secure (Works reliably across processes on AOSP/VoltageOS)
        try {
            val v = Settings.Secure.getInt(context.contentResolver, key, -999)
            if (v != -999) return v
        } catch (e: Throwable) {
            // Ignore
        }

        // 2. LineageSettings via reflection
        try {
            val clazz = Class.forName("lineageos.providers.LineageSettings\$System")
            val method = clazz.getMethod(
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val v = method.invoke(null, context.contentResolver, key, -999) as? Int
            if (v != null && v != -999) return v
        } catch (e: Throwable) {
            // Ignore
        }

        // 3. SharedPreferences fallback
        return readPrefInt(key, def)
    }

    private fun readPrefInt(key: String, def: Int): Int {
        val prefContexts = mutableListOf(packageContext)
        try {
            prefContexts.add(packageContext.createDeviceProtectedStorageContext())
        } catch (e: Throwable) {
            // Ignore
        }

        for (ctx in prefContexts) {
            try {
                val sp = ctx.getSharedPreferences(
                    packageContext.packageName + "_preferences",
                    Context.MODE_PRIVATE,
                )
                if (sp.contains(key)) {
                    return sp.getString(key, def.toString())?.toIntOrNull()
                        ?: sp.getInt(key, def)
                }
            } catch (e: Throwable) {
                // Ignore
            }
        }
        return def
    }

    private fun handleMode(position: Int, firstRun: Boolean) {
        currentPosition = position
        refreshSettings()

        val mode =
            when (position) {
                POSITION_TOP -> topPosition
                POSITION_MIDDLE -> middlePosition
                POSITION_BOTTOM -> bottomPosition
                else -> return
            }

        executorService.submit {
            when (mode) {
                AudioManager.RINGER_MODE_SILENT -> {
                    setZenMode(Settings.Global.ZEN_MODE_OFF)
                    audioManager.ringerModeInternal = mode
                    if (muteMedia) {
                        audioManager.adjustVolume(AudioManager.ADJUST_MUTE, 0)
                        wasMuted = true
                    }
                }
                AudioManager.RINGER_MODE_VIBRATE,
                AudioManager.RINGER_MODE_NORMAL -> {
                    setZenMode(Settings.Global.ZEN_MODE_OFF)
                    audioManager.ringerModeInternal = mode
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                ZEN_PRIORITY_ONLY,
                ZEN_TOTAL_SILENCE,
                ZEN_ALARMS_ONLY -> {
                    audioManager.ringerModeInternal = AudioManager.RINGER_MODE_NORMAL
                    setZenMode(mode - ZEN_OFFSET)
                    if (muteMedia && wasMuted) {
                        audioManager.adjustVolume(AudioManager.ADJUST_UNMUTE, 0)
                    }
                }
                TORCH_ON,
                TORCH_OFF -> {
                    try {
                        val cameraId =
                            cameraManager.cameraIdList.firstOrNull { id ->
                                cameraManager
                                    .getCameraCharacteristics(id)
                                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                            }
                        if (cameraId != null) {
                            cameraManager.setTorchMode(cameraId, mode == TORCH_ON)
                        }
                    } catch (e: Throwable) {
                        // Ignore
                    }
                }
            }

            if (!firstRun) {
                if (showDialog) sendNotification(position, mode, invertColors, dialogLocation)
                vibrateIfNeeded(mode)
            }
        }
    }

    private fun setZenMode(zenMode: Int) {
        try {
            // Set zen mode
            notificationManager.setZenMode(zenMode, null, TAG)

            // Wait until zen mode change is committed with a safety timeout (max 500ms)
            var attempts = 0
            while (notificationManager.zenMode != zenMode && attempts < 50) {
                Thread.sleep(10)
                attempts++
            }
        } catch (e: Throwable) {
            // Ignore if restricted
        }
    }

    private fun sendNotification(
        position: Int,
        mode: Int,
        invertColors: Boolean,
        dialogLocation: Int,
    ) {
        context.sendBroadcast(
            Intent(CHANGED_ACTION).apply {
                putExtra("position", position)
                putExtra("mode", mode)
                putExtra("invertColors", invertColors)
                putExtra(DIALOG_LOCATION, dialogLocation)
                putExtra(ALERT_SLIDER_ISLAND_STYLE, islandStyle)
            }
        )
    }

    companion object {
        private const val TAG = "KeyHandler"

        // Intent actions
        const val CHANGED_ACTION = "org.lineageos.settings.UPDATE_SETTINGS"
        const val CYCLE_ACTION = "org.lineageos.settings.CYCLE_RINGER_MODE"

        // Slider key positions
        const val POSITION_TOP = 1
        const val POSITION_MIDDLE = 2
        const val POSITION_BOTTOM = 3

        // Preference keys
        const val DIALOG_LOCATION = "config_dialog_location"
        const val DIALOG_LOCATION_SIDE = 0
        const val DIALOG_LOCATION_ISLAND = 1

        const val ALERT_SLIDER_ISLAND_STYLE = "alert_slider_island_style"
        const val ISLAND_STYLE_STANDARD = 0
        const val ISLAND_STYLE_MINIMAL = 1

        const val ALERT_SLIDER_TOP_KEY = "config_top_position"
        const val ALERT_SLIDER_MIDDLE_KEY = "config_middle_position"
        const val ALERT_SLIDER_BOTTOM_KEY = "config_bottom_position"
        const val MUTE_MEDIA_WITH_SILENT = "config_mute_media"
        const val SHOW_DIALOG = "config_show_dialog"
        const val INVERT_COLORS = "config_invert_colors"

        // ZEN constants
        private const val ZEN_OFFSET = 2
        const val ZEN_PRIORITY_ONLY = 3
        const val ZEN_TOTAL_SILENCE = 4
        const val ZEN_ALARMS_ONLY = 5

        // Torch constants
        private const val TORCH_OFFSET = 8
        const val TORCH_ON = TORCH_OFFSET + 0
        const val TORCH_OFF = TORCH_OFFSET + 1

        // Vibration attributes
        private val HARDWARE_FEEDBACK_VIBRATION_ATTRIBUTES =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)

        // Vibration effects
        private val MODE_NORMAL_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_HEAVY_CLICK)
        private val MODE_VIBRATION_EFFECT = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }
}
