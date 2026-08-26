package com.chad.triptime.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chad.triptime.model.DistanceUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "trip_time_prefs")

/**
 * The only thing TripTime remembers between launches: whether the user wants miles or
 * kilometres. It lives in Jetpack DataStore, which — unlike raw SharedPreferences — reads and
 * writes off the main thread and exposes changes as a [Flow].
 *
 * The OpenRouteService key used to live here too, back when users supplied their own. It is now
 * baked in at build time instead (see DECISIONS.md D-005), so there is nothing secret stored
 * on-device.
 */
class PreferencesStore(private val context: Context) {

    private object Keys {
        val UNIT = stringPreferencesKey("distance_unit")
    }

    val unit: Flow<DistanceUnit> = context.dataStore.data.map { prefs ->
        // Matched by name across all entries rather than case-by-case, so adding a unit to the
        // enum doesn't silently fall through to the default here.
        val stored = prefs[Keys.UNIT]
        DistanceUnit.entries.firstOrNull { it.name == stored } ?: DistanceUnit.IMPERIAL
    }

    suspend fun setUnit(unit: DistanceUnit) {
        context.dataStore.edit { prefs -> prefs[Keys.UNIT] = unit.name }
    }
}
