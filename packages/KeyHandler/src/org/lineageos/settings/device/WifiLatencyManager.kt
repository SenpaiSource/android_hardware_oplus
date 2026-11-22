/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.app.ActivityManager
import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * Dynamic Wi-Fi Low-Latency & Dwell Mode Manager for OPlus devices.
 *
 * Replicates stock OxygenOS/ColorOS dynamic scan dwell clamping:
 * - Idle / Normal: 60ms resting baseline
 * - Android Auto / P2P: 30ms dwell time (WIFI_MODE_FULL_LOW_LATENCY)
 * - TWS Earbuds / Bluetooth A2DP: 30ms dwell time (Bluetooth Coexistence / BTC)
 * - Mobile Hotspot (SoftAP): 30ms dwell time
 * - Online Games: 30ms dwell time (Ultra-Low Latency Mode 4)
 */
object WifiLatencyManager {
    private const val TAG = "OplusWifiLatencyManager"

    // Active state bitmask flags
    private const val FLAG_P2P = 1 shl 0       // Wireless Android Auto / Miracast
    private const val FLAG_BT_A2DP = 1 shl 1   // TWS Earbuds / Car Bluetooth A2DP
    private const val FLAG_SOFTAP = 1 shl 2    // Mobile Hotspot
    private const val FLAG_GAME = 1 shl 3      // Online Game in Foreground

    // SoftAP / Tethering broadcast constants (marked @SystemApi in WifiManager)
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val WIFI_AP_STATE_DISABLING = 10
    private const val WIFI_AP_STATE_DISABLED = 11
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val WIFI_AP_STATE_FAILED = 14

    @Volatile
    private var activeFlags = 0

    private var wifiLock: WifiManager.WifiLock? = null
    private var isInitialized = false

    private val handlerThread = HandlerThread("OplusWifiLatencyHandler").apply { start() }
    private val handler = Handler(handlerThread.looper)

    fun init(context: Context) {
        synchronized(this) {
            if (isInitialized) return
            isInitialized = true
        }

        Log.i(TAG, "Initializing OplusWifiLatencyManager")
        handler.post {
            setupP2pListener(context)
            setupBluetoothListener(context)
            setupSoftApListener(context)
            setupGameListener(context)
            queryInitialStates(context)
        }
    }

    fun onBootCompleted(context: Context) {
        handler.post {
            queryInitialStates(context)
        }
    }

    private fun queryInitialStates(context: Context) {
        // Query Bluetooth A2DP initial state
        try {
            val btManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = btManager?.adapter
            adapter?.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            val connectedDevices = proxy.connectedDevices
                            val isConnected = connectedDevices.isNotEmpty()
                            Log.d(TAG, "Queried Bluetooth A2DP devices: count=${connectedDevices.size}")
                            updateState(context, FLAG_BT_A2DP, isConnected)
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {}
                },
                BluetoothProfile.A2DP,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query initial Bluetooth state", e)
        }

        // Query SoftAP initial state via sticky broadcast
        try {
            val sticky =
                context.registerReceiver(
                    null,
                    IntentFilter(ACTION_WIFI_AP_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED,
                )
            val state = sticky?.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED) ?: WIFI_AP_STATE_DISABLED
            val isEnabled = state == WIFI_AP_STATE_ENABLED
            updateState(context, FLAG_SOFTAP, isEnabled)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query initial SoftAP state", e)
        }
    }

    /**
     * Wireless Android Auto & Miracast Wi-Fi Direct listener.
     */
    @Suppress("DEPRECATION")
    private fun setupP2pListener(context: Context) {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) {
                        val networkInfo =
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                        val isConnected = networkInfo?.isConnected == true
                        Log.d(TAG, "P2P connection state changed: isConnected=$isConnected")
                        updateState(context, FLAG_P2P, isConnected)
                    }
                }
            },
            filter,
            null,
            handler,
            Context.RECEIVER_EXPORTED,
        )
    }

    /**
     * Bluetooth A2DP (TWS Earbuds / Car Bluetooth Music) listener.
     */
    private fun setupBluetoothListener(context: Context) {
        val filter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                        val state =
                            intent.getIntExtra(
                                BluetoothProfile.EXTRA_STATE,
                                BluetoothProfile.STATE_DISCONNECTED,
                            )
                        val isConnected = state == BluetoothProfile.STATE_CONNECTED
                        Log.d(TAG, "Bluetooth A2DP state changed: state=$state, isConnected=$isConnected")
                        updateState(context, FLAG_BT_A2DP, isConnected)
                    }
                }
            },
            filter,
            null,
            handler,
            Context.RECEIVER_EXPORTED,
        )
    }

    /**
     * Mobile Hotspot (SoftAP) listener.
     */
    private fun setupSoftApListener(context: Context) {
        val filter = IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == ACTION_WIFI_AP_STATE_CHANGED) {
                        val state =
                            intent.getIntExtra(
                                EXTRA_WIFI_AP_STATE,
                                WIFI_AP_STATE_DISABLED,
                            )
                        val isEnabled = state == WIFI_AP_STATE_ENABLED
                        Log.d(TAG, "SoftAP state changed: isEnabled=$isEnabled")
                        updateState(context, FLAG_SOFTAP, isEnabled)
                    }
                }
            },
            filter,
            null,
            handler,
            Context.RECEIVER_EXPORTED,
        )
    }

    /**
     * Foreground Game App detection listener.
     */
    private fun setupGameListener(context: Context) {
        try {
            val atm = ActivityTaskManager.getService()
            atm?.registerTaskStackListener(
                object : TaskStackListener() {
                    override fun onTaskStackChanged() {
                        checkForegroundApp(context)
                    }
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register TaskStackListener for game detection", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun checkForegroundApp(context: Context) {
        handler.post {
            var isGame = false
            try {
                val am = context.getSystemService(ActivityManager::class.java)
                val topTask = am?.getRunningTasks(1)?.firstOrNull()
                val pkgName = topTask?.topActivity?.packageName
                if (pkgName != null) {
                    val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
                    isGame = (appInfo.category == ApplicationInfo.CATEGORY_GAME) ||
                            ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
                }
            } catch (e: Exception) {
                // Ignore package not found or transient errors
            }
            updateState(context, FLAG_GAME, isGame)
        }
    }

    /**
     * Synchronized state bitmask updater. Acquires or releases the
     * WIFI_MODE_FULL_LOW_LATENCY lock dynamically.
     */
    @Synchronized
    private fun updateState(context: Context, flag: Int, active: Boolean) {
        val previousFlags = activeFlags
        activeFlags = if (active) {
            activeFlags or flag
        } else {
            activeFlags and flag.inv()
        }

        if (activeFlags == previousFlags) return

        Log.d(
            TAG,
            "updateState: flag=0x${flag.toString(16)}, active=$active -> activeFlags=0x${activeFlags.toString(16)}",
        )

        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return

        if (activeFlags != 0) {
            if (wifiLock == null) {
                wifiLock =
                    wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                        TAG,
                    ).apply {
                        setReferenceCounted(false)
                    }
            }
            if (wifiLock?.isHeld != true) {
                try {
                    wifiLock?.acquire()
                    Log.i(
                        TAG,
                        "Acquired WIFI_MODE_FULL_LOW_LATENCY lock (activeFlags=0x${activeFlags.toString(16)})",
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to acquire WifiLock", e)
                }
            }
        } else {
            if (wifiLock?.isHeld == true) {
                try {
                    wifiLock?.release()
                    Log.i(TAG, "Released WIFI_MODE_FULL_LOW_LATENCY lock (returned to baseline)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release WifiLock", e)
                }
            }
        }
    }
}
