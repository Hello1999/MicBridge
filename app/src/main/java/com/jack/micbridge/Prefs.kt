package com.jack.micbridge

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ptt", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = sp.getString(KEY_ADDRESS, null)
        set(value) = sp.edit { putString(KEY_ADDRESS, value) }

    var deviceName: String?
        get() = sp.getString(KEY_NAME, null)
        set(value) = sp.edit { putString(KEY_NAME, value) }

    var method: MuteMethod
        get() = runCatching { MuteMethod.valueOf(sp.getString(KEY_METHOD, null)!!) }
            .getOrDefault(MuteMethod.AUDIO_MANAGER)
        set(value) = sp.edit { putString(KEY_METHOD, value.name) }

    private companion object {
        const val KEY_ADDRESS = "device_address"
        const val KEY_NAME = "device_name"
        const val KEY_METHOD = "mute_method"
    }
}
