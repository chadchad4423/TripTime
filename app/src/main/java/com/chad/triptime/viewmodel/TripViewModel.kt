package com.chad.triptime.viewmodel

import android.util.Log
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chad.triptime.data.OrsException
import com.chad.triptime.BuildConfig
import com.chad.triptime.data.AppConfig
import com.chad.triptime.data.RemoteConfigFetcher
import com.chad.triptime.data.RemoteConfigStore
import com.chad.triptime.data.PreferencesStore
import com.chad.triptime.data.TripRepository
import com.chad.triptime.model.DistanceUnit
import com.chad.triptime.model.Place
import com.chad.triptime.model.SillyUnit
import com.chad.triptime.model.SillyUnits
import com.chad.triptime.model.TripResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the two location fields a piece of UI state or an action refers to. */
enum class TripField { ORIGIN, DESTINATION }

private const val TAG = "TripViewModel"

data class TripUiState(
    val originQuery: String = "",
    val destinationQuery: String = "",
    /**
     * The address picker, when it is open. Suggestions are no longer an overlay on the trip
     * screen — see DECISIONS.md D-022. Null means the picker is closed.
     */
    val picker: PickerState? = null,
    val originSelected: Place? = null,
    val destinationSelected: Place? = null,
    val unit: DistanceUnit = DistanceUnit.IMPERIAL,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tripResult: TripResult? = null,
    /** A one-line notice from remote config: a broadcast message, or a nudge to update. Null
     * whenever there is nothing to say, which is the normal case. */
    val notice: String? = null,

    /** Chosen once per calculated trip, never re-rolled during recomposition. */
    val sillyUnit: SillyUnit? = null,
)

/**
 * The address picker: which field asked for it, what was searched, and what came back.
 *
 * A screen of its own rather than a dropdown over the trip screen. The dropdown had to be sized
 * against the keyboard it was covering, and no arrangement of it left room for more than a couple
 * of results on the lower field — see D-022.
 */
data class PickerState(
    val field: TripField,
    val query: String,
    val isLoading: Boolean = false,
    val results: List<Place> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Holds everything the Trip screen shows. Address text and autocomplete are the trickiest part:
 * each field's typed text lives in its own debounced [MutableStateFlow] so a fast typist doesn't
 * fire an OpenRouteService request per keystroke — see [observeQuery].
 */
class TripViewModel(
    private val repository: TripRepository,
    private val preferencesStore: PreferencesStore,
    private val configStore: RemoteConfigStore,
    private val configFetcher: RemoteConfigFetcher,
) : ViewModel() {

    /**
     * Session-only caches, so retyping, backspacing and re-pressing Calculate cost nothing.
     *
     * Geocoding is the binding quota — roughly 5x tighter than directions once autocomplete is
     * counted — so cutting redundant lookups stretches the shared key much further than any
     * change to the routing side would. Deliberately in memory and nowhere else: persisting them
     * would make `ui/PrivacyScreen.kt`'s "trips are not saved, close the app and they are gone"
     * false, and that claim is worth more than the handful of calls it would save (D-021).
     */
    private val suggestionCache = LruCache<String, List<Place>>(CACHE_ENTRIES)
    private val tripCache = LruCache<Pair<Place, Place>, TripResult>(CACHE_ENTRIES)

    private val _uiState = MutableStateFlow(TripUiState())
    val uiState: StateFlow<TripUiState> = _uiState.asStateFlow()

    private val originQuery = MutableStateFlow("")
    private val destinationQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            preferencesStore.unit.collectLatest { unit -> _uiState.update { it.copy(unit = unit) } }
        }
        // Fired and forgotten. Nothing waits on it, nothing fails if it never returns, and the
        // app is fully usable before it does — the compiled-in defaults are already in place.
        viewModelScope.launch {
            val config = configFetcher.fetch() ?: return@launch
            // The endpoint and key go into reserve, unused until something actually fails; the
            // notice is advisory and safe to show at once. See DECISIONS.md D-020.
            val notice = noticeFor(config)
            configStore.offer(config, notice)
            _uiState.update { it.copy(notice = notice) }
        }

    }

    /** Debounces typing, drops the request entirely once the field is empty, and always keeps
     * only the latest in-flight lookup. */
    @OptIn(FlowPreview::class)
    fun onQueryChange(field: TripField, text: String) {
        _uiState.update { state ->
            when (field) {
                TripField.ORIGIN -> state.copy(originQuery = text, originSelected = null, tripResult = null, errorMessage = null)
                TripField.DESTINATION -> state.copy(destinationQuery = text, destinationSelected = null, tripResult = null, errorMessage = null)
            }
        }
        when (field) {
            TripField.ORIGIN -> originQuery.value = text
            TripField.DESTINATION -> destinationQuery.value = text
        }
    }

    /** Chosen from the picker: fill the field, remember the coordinates, and close the picker. */
    fun onSuggestionPicked(field: TripField, place: Place) {
        _uiState.update { state ->
            when (field) {
                TripField.ORIGIN -> state.copy(
                    originQuery = place.label,
                    originSelected = place,
                    picker = null,
                    tripResult = null,
                )
                TripField.DESTINATION -> state.copy(
                    destinationQuery = place.label,
                    destinationSelected = place,
                    picker = null,
                    tripResult = null,
                )
            }
        }
        // No lookup is fired for the label just chosen. Nothing would re-open now that the
        // picker is a screen, but it would still spend a geocoding request to re-resolve an
        // address whose coordinates are already in hand.
    }

    /**
     * Opens the address picker for [field] and starts a search for whatever has been typed there.
     *
     * Explicit rather than automatic: nothing is looked up until the user asks, so the results
     * never arrive mid-word (D-022). The old debounce existed only to guess when typing had
     * stopped, and guessing is what made the list appear at the wrong moment.
     */
    fun openPicker(field: TripField) {
        val query = when (field) {
            TripField.ORIGIN -> _uiState.value.originQuery
            TripField.DESTINATION -> _uiState.value.destinationQuery
        }.trim()
        if (query.length < MIN_QUERY_LENGTH) return

        _uiState.update { it.copy(picker = PickerState(field = field, query = query, isLoading = true)) }

        viewModelScope.launch {
            // Destination results are biased toward wherever the trip starts, so a vague
            // destination ("main st") offers streets near the origin rather than the most globally
            // prominent match. TripTime never reads the phone's location for this (D-006).
            val focus = if (field == TripField.DESTINATION) _uiState.value.originSelected else null
            val cacheKey = "${focus?.latitude},${focus?.longitude}|$query"
            val cached = suggestionCache.get(cacheKey)
            val results = cached ?: runCatching { repository.suggestions(query, focus) }
                .onFailure { Log.w(TAG, "Search failed for \"$query\"", it) }
                .getOrNull()

            _uiState.update { state ->
                // The user may have backed out while the request was in flight; do not reopen it.
                val open = state.picker ?: return@update state
                if (open.field != field || open.query != query) return@update state
                when {
                    results == null -> state.copy(
                        picker = open.copy(
                            isLoading = false,
                            errorMessage = "Couldn't reach the address service. Check the connection and try again.",
                        )
                    )
                    else -> {
                        if (results.isNotEmpty()) suggestionCache.put(cacheKey, results)
                        state.copy(picker = open.copy(isLoading = false, results = results))
                    }
                }
            }
        }
    }

    /** Leaves the picker without choosing, keeping whatever was typed. */
    fun closePicker() {
        _uiState.update { it.copy(picker = null) }
    }

    /** Sets the unit outright rather than flipping it: the UI offers "mi" and "km" as two
     * separate labels, so tapping the one already selected should be a no-op, not a switch. */
    fun selectUnit(unit: DistanceUnit) {
        if (unit == _uiState.value.unit) return
        viewModelScope.launch { preferencesStore.setUnit(unit) }
    }

    fun calculate() {
        val state = _uiState.value
        if (state.originQuery.isBlank() || state.destinationQuery.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter both a starting point and a destination.") }
            return
        }
        viewModelScope.launch {
            // Both suggestion lists are closed here, not just the keyboard. Picking a suggestion
            // already clears its own list, but calculating from freely typed text never did — so
            // pressing the keyboard's Go key left the destination list drawn as a Popup over the
            // answer area, hiding the result the press had just asked for. Committing to a
            // calculation is the end of choosing an address, for either field.
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    tripResult = null,
                )
            }
            try {
                val origin = state.originSelected
                    ?: repository.resolve(state.originQuery, focus = null)
                    ?: throw OrsException("Couldn't find \"${state.originQuery}\". Try a more complete address.")
                // Same bias as the destination suggestions: once the origin is known, resolve a
                // freely typed destination relative to it.
                val destination = state.destinationSelected
                    ?: repository.resolve(state.destinationQuery, focus = origin)
                    ?: throw OrsException("Couldn't find \"${state.destinationQuery}\". Try a more complete address.")

                // Same two places as last time? Return the answer already in hand. This makes a
                // repeated Calculate instant and free, which removes button-mashing as a way to
                // burn the shared quota and, incidentally, is the fastest the app ever feels.
                val key = origin to destination
                val trip = tripCache.get(key) ?: repository.calculateTrip(origin, destination)
                    .also { tripCache.put(key, it) }
                _uiState.update {
                    it.copy(isLoading = false, tripResult = trip, sillyUnit = SillyUnits.pick(trip.distanceMeters))
                }
            } catch (e: OrsException) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                Log.w(TAG, "Trip calculation failed", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "Something went wrong. Try again.") }
            }
        }
    }
}

/** Bound on both session caches. Large enough that ordinary use never evicts, small enough that a
 * very long session cannot grow memory without limit. */
private const val CACHE_ENTRIES = 50

/** Below this the picker will not search: shorter queries match too much to be useful, and
 * it is the geocoding quota being spent (D-021). */
private const val MIN_QUERY_LENGTH = 3


/**
 * What, if anything, to show the user from remote config: an explicit broadcast message wins, and
 * otherwise a nudge to update when a newer version has been published.
 *
 * The version comparison is numeric per dot-separated part rather than a string compare, so a
 * build that is *ahead* of the published one — a local debug build, say — is never told to
 * downgrade itself. Anything unparseable is treated as "nothing to say" rather than guessed at.
 */
internal fun noticeFor(config: AppConfig, currentVersion: String = BuildConfig.VERSION_NAME): String? {
    config.message?.takeIf { it.isNotBlank() }?.let { return it }
    val latest = config.latestVersion?.takeIf { it.isNotBlank() } ?: return null
    if (!isNewer(latest, currentVersion)) return null
    return "TripTime $latest is available. This copy is $currentVersion."
}

/** Numeric per dot-separated part, so a build that is *ahead* of the published one is never told
 * to downgrade. Anything unparseable means "say nothing" rather than a guess. */
private fun isNewer(candidate: String, current: String): Boolean {
    val a = parseVersion(candidate) ?: return false
    val b = parseVersion(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun parseVersion(value: String): List<Int>? {
    val parts = value.trim().split(".")
    if (parts.isEmpty()) return null
    val numbers = ArrayList<Int>(parts.size)
    for (part in parts) {
        numbers.add(part.toIntOrNull() ?: return null)
    }
    return numbers
}
