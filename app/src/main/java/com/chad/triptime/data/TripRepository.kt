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
/** How many addresses the picker offers. Matches what autocomplete is asked for. */
private const val SUGGESTION_COUNT = 5

class TripRepository(private val orsClient: OrsClient) {

    /** Autocomplete suggestions for [query], biased toward [focus] when one is known. */
    /**
     * Addresses to offer for [query], biased toward [focus] when one is known.
     *
     * Autocomplete first, then search if it found nothing. The two endpoints fail in opposite
     * directions and neither is a superset of the other: autocomplete is the one that understands
     * partial words ("Denv" -> Denver), while search is the one that understands a complete street
     * address with a house number and a postcode — autocomplete returns nothing at all for those,
     * which is how a real address a user typed in full came back empty.
     *
     * The fallback costs a second request only when the first found nothing, which is exactly the
     * case where spending one is worth it.
     */
    suspend fun suggestions(query: String, focus: Place?): List<Place> {
        val fromAutocomplete = orsClient.autocomplete(query, focus = focus)
        if (fromAutocomplete.isNotEmpty()) return fromAutocomplete
        return orsClient.search(query, focus = focus, size = SUGGESTION_COUNT)
    }

    /** Resolves free-typed text the user never picked a suggestion for into one best-match
     * place, so pressing Calculate always works even without using autocomplete. */
    suspend fun resolve(query: String, focus: Place?): Place? =
        orsClient.search(query, focus = focus).firstOrNull()

    suspend fun calculateTrip(origin: Place, destination: Place): TripResult =
        orsClient.drivingDirections(origin, destination)
}
