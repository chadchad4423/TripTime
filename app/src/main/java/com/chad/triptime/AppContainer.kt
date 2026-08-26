package com.chad.triptime

import android.content.Context
import com.chad.triptime.data.OrsClient
import com.chad.triptime.data.RemoteConfigFetcher
import com.chad.triptime.data.RemoteConfigStore
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

    /**
     * Where requests go, and anything the app needs to tell the user. Starts at the compiled-in
     * defaults and is replaced only if a fetch succeeds — see DECISIONS.md D-020. Held here rather
     * than inside the client so the ViewModel can read the message and version fields too.
     */
    val remoteConfigStore = RemoteConfigStore()
    val remoteConfigFetcher = RemoteConfigFetcher()

    private val orsClient = OrsClient(remoteConfigStore)
    val tripRepository = TripRepository(orsClient)
}
