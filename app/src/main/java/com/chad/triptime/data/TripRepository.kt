package com.chad.triptime.data

import com.chad.triptime.model.Place
import com.chad.triptime.model.TripResult

/**
 * The one seam between the ViewModel and the network layer. Kept intentionally thin: its job is
 * to give [com.chad.triptime.viewmodel.TripViewModel] three plain suspend functions to call, so
 * the ViewModel never has to know [OrsClient] exists.
 *
 * [focus] is how TripTime makes destination suggestions useful: the ViewModel passes the
 * already-resolved starting point when looking up a destination, so "main st" offers streets
 * near where the trip starts. TripTime does not read the phone's location for this — see
 * DECISIONS.md D-006.
 */
class TripRepository(private val orsClient: OrsClient) {

    /** Autocomplete suggestions for [query], biased toward [focus] when one is known. */
    suspend fun suggestions(query: String, focus: Place?): List<Place> =
        orsClient.autocomplete(query, focus = focus)

    /** Resolves free-typed text the user never picked a suggestion for into one best-match
     * place, so pressing Calculate always works even without using autocomplete. */
    suspend fun resolve(query: String, focus: Place?): Place? =
        orsClient.search(query, focus = focus).firstOrNull()

    suspend fun calculateTrip(origin: Place, destination: Place): TripResult =
        orsClient.drivingDirections(origin, destination)
}
