/*
 * SPDX-FileCopyrightText: 2021-2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.device

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment

class ButtonSettingsFragment :
    SettingsBasePreferenceFragment(), Preference.OnPreferenceChangeListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        sanitizePreferences(context)

        // Ensure default preference values are populated on first launch
        PreferenceManager.setDefaultValues(context, R.xml.button_panel, false)

        setPreferencesFromResource(R.xml.button_panel, rootKey)

        // Ensure Dynamic Island location preference is visible
        val locationPref = findPreference<ListPreference>(KeyHandler.DIALOG_LOCATION)
        if (locationPref != null) {
            locationPref.isVisible = true
        }

        // Attach listeners to all alert slider preference items
        val keys = listOf(
            KeyHandler.SHOW_DIALOG,
            KeyHandler.DIALOG_LOCATION,
            KeyHandler.INVERT_COLORS,
            KeyHandler.MUTE_MEDIA_WITH_SILENT,
            KeyHandler.ALERT_SLIDER_TOP_KEY,
            KeyHandler.ALERT_SLIDER_MIDDLE_KEY,
            KeyHandler.ALERT_SLIDER_BOTTOM_KEY,
        )

        for (key in keys) {
            findPreference<Preference>(key)?.onPreferenceChangeListener = this
        }

        // Initial broadcast to push all current settings to KeyHandler and SystemUI
        broadcastAllSettings(context)
    }

    override fun onResume() {
        super.onResume()
        broadcastAllSettings(requireContext())
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val context = requireContext()
        // Save the updated setting immediately across storage backends
        savePreferenceValue(context, preference.key, newValue)
        // Broadcast all settings with the new value applied
        broadcastSettings(context, preference.key, newValue)
        return true
    }

    private fun savePreferenceValue(context: Context, key: String, newValue: Any) {
        val intVal = when (newValue) {
            is Boolean -> if (newValue) 1 else 0
            is String -> newValue.toIntOrNull() ?: 0
            is Int -> newValue
            else -> 0
        }

        // 1. Settings.Secure (Works across AOSP, VoltageOS, and Lunaris without restrictions)
        try {
            Settings.Secure.putInt(context.contentResolver, key, intVal)
        } catch (e: Throwable) {
            // Ignore if restricted
        }

        // 2. LineageSettings via reflection
        try {
            val clazz = Class.forName("lineageos.providers.LineageSettings\$System")
            val method = clazz.getMethod(
                "putInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            method.invoke(null, context.contentResolver, key, intVal)
        } catch (e: Throwable) {
            // Ignore on ROMs without LineageSDK
        }

        // 3. Persist to SharedPreferences (both default and device-protected contexts)
        val contexts = mutableListOf(context)
        try {
            contexts.add(context.createDeviceProtectedStorageContext())
        } catch (e: Throwable) {
            // Ignore
        }

        for (ctx in contexts) {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val editor = prefs.edit()
                if (newValue is Boolean) {
                    editor.putBoolean(key, newValue)
                } else {
                    editor.putString(key, newValue.toString())
                }
                editor.commit()
            } catch (e: Throwable) {
                // Ignore
            }

            try {
                val prefs = ctx.getSharedPreferences(
                    ctx.packageName + "_preferences",
                    Context.MODE_PRIVATE,
                )
                val editor = prefs.edit()
                if (newValue is Boolean) {
                    editor.putBoolean(key, newValue)
                } else {
                    editor.putString(key, newValue.toString())
                }
                editor.commit()
            } catch (e: Throwable) {
                // Ignore
            }
        }
    }

    private fun broadcastSettings(
        context: Context,
        updatedKey: String? = null,
        updatedValue: Any? = null,
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        fun getBool(key: String, def: Boolean): Boolean {
            if (key == updatedKey && updatedValue != null) {
                return when (updatedValue) {
                    is Boolean -> updatedValue
                    is String -> updatedValue == "1" || updatedValue.equals("true", ignoreCase = true)
                    is Number -> updatedValue.toInt() == 1
                    else -> def
                }
            }
            try {
                val v = Settings.Secure.getInt(context.contentResolver, key, -999)
                if (v != -999) return v == 1
            } catch (e: Throwable) {
                // Ignore
            }
            return prefs.getBoolean(key, def)
        }

        fun getInt(key: String, def: Int): Int {
            if (key == updatedKey && updatedValue != null) {
                return when (updatedValue) {
                    is Number -> updatedValue.toInt()
                    is String -> updatedValue.toIntOrNull() ?: def
                    is Boolean -> if (updatedValue) 1 else 0
                    else -> def
                }
            }
            try {
                val v = Settings.Secure.getInt(context.contentResolver, key, -999)
                if (v != -999) return v
            } catch (e: Throwable) {
                // Ignore
            }
            return prefs.getString(key, def.toString())?.toIntOrNull() ?: prefs.getInt(key, def)
        }

        val showDialog = getBool(KeyHandler.SHOW_DIALOG, true)
        val dialogLocation = getInt(KeyHandler.DIALOG_LOCATION, KeyHandler.DIALOG_LOCATION_SIDE)
        val invertColors = getBool(KeyHandler.INVERT_COLORS, false)
        val muteMedia = getBool(KeyHandler.MUTE_MEDIA_WITH_SILENT, false)
        val topPos = getInt(KeyHandler.ALERT_SLIDER_TOP_KEY, 0)
        val midPos = getInt(KeyHandler.ALERT_SLIDER_MIDDLE_KEY, 1)
        val botPos = getInt(KeyHandler.ALERT_SLIDER_BOTTOM_KEY, 2)

        val intent =
            Intent(KeyHandler.CHANGED_ACTION).apply {
                flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND
                putExtra(KeyHandler.SHOW_DIALOG, showDialog)
                putExtra(KeyHandler.DIALOG_LOCATION, dialogLocation)
                putExtra(KeyHandler.INVERT_COLORS, invertColors)
                putExtra(KeyHandler.MUTE_MEDIA_WITH_SILENT, muteMedia)
                putExtra(KeyHandler.ALERT_SLIDER_TOP_KEY, topPos)
                putExtra(KeyHandler.ALERT_SLIDER_MIDDLE_KEY, midPos)
                putExtra(KeyHandler.ALERT_SLIDER_BOTTOM_KEY, botPos)
            }
        context.sendBroadcast(intent)
    }

    private fun broadcastAllSettings(context: Context) {
        broadcastSettings(context, null, null)
    }

    private fun sanitizePreferences(context: Context) {
        val contexts = mutableListOf(context)
        try {
            contexts.add(context.createDeviceProtectedStorageContext())
        } catch (e: Throwable) {
            // Ignore
        }

        val booleanKeys = setOf(
            KeyHandler.SHOW_DIALOG,
            KeyHandler.INVERT_COLORS,
            KeyHandler.MUTE_MEDIA_WITH_SILENT,
        )

        val stringKeys = setOf(
            KeyHandler.DIALOG_LOCATION,
            KeyHandler.ALERT_SLIDER_TOP_KEY,
            KeyHandler.ALERT_SLIDER_MIDDLE_KEY,
            KeyHandler.ALERT_SLIDER_BOTTOM_KEY,
        )

        for (ctx in contexts) {
            val prefFiles = listOf(
                PreferenceManager.getDefaultSharedPreferences(ctx),
                ctx.getSharedPreferences(ctx.packageName + "_preferences", Context.MODE_PRIVATE),
            )
            for (prefs in prefFiles) {
                try {
                    val all = prefs.all
                    val editor = prefs.edit()
                    var changed = false

                    for (key in booleanKeys) {
                        val value = all[key] ?: continue
                        if (value !is Boolean) {
                            val boolVal = when (value) {
                                is Number -> value.toInt() == 1
                                is String -> value == "1" || value.equals("true", ignoreCase = true)
                                else -> false
                            }
                            editor.remove(key).putBoolean(key, boolVal)
                            changed = true
                        }
                    }

                    for (key in stringKeys) {
                        val value = all[key] ?: continue
                        if (value !is String) {
                            val strVal = value.toString()
                            editor.remove(key).putString(key, strVal)
                            changed = true
                        }
                    }

                    if (changed) {
                        editor.commit()
                    }
                } catch (e: Throwable) {
                    // Ignore
                }
            }
        }
    }
}
