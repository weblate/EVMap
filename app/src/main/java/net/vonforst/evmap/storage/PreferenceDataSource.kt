package net.vonforst.evmap.storage

import android.content.Context
import androidx.preference.PreferenceManager

class PreferenceDataSource(context: Context) {
    val sp = PreferenceManager.getDefaultSharedPreferences(context)

    var navigateUseMaps: Boolean
        get() = sp.getBoolean("navigate_use_maps", true)
        set(value) {
            sp.edit().putBoolean("navigate_use_maps", value).apply()
        }
}