package com.chad.triptime

import android.content.Context
import com.chad.triptime.data.OrsClient
import com.chad.triptime.data.PreferencesStore
import com.chad.triptime.data.TripRepository

/**
 * TripTime's whole dependency graph, by hand. The app is small enough (one repository, one
 * network client, one preferences store) that a dependency-injection framework like Hilt would
 * add more ceremony than it would save — this class is the entire "DI container."
 *
 * Built once in [TripTimeApplication] and handed to the ViewModel through its factory.
 */
class AppContainer(context: Context) {
    val preferencesStore = PreferencesStore(context.applicationContext)

    private val orsClient = OrsClient(apiKey = BuildConfig.ORS_API_KEY)
    val tripRepository = TripRepository(orsClient)
}
