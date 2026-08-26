package com.chad.triptime.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chad.triptime.data.OrsException
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the two location fields a piece of UI state or an action refers to. */
enum class TripField { ORIGIN, DESTINATION }

private const val TAG = "TripViewModel"

data class TripUiState(
    val originQuery: String = "",
    val destinationQuery: String = "",
    val originSuggestions: List<Place> = emptyList(),
    val destinationSuggestions: List<Place> = emptyList(),
    val originSelected: Place? = null,
    val destinationSelected: Place? = null,
    val unit: DistanceUnit = DistanceUnit.IMPERIAL,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val tripResult: TripResult? = null,
    /** Chosen once per calculated trip, never re-rolled during recomposition. */
    val sillyUnit: SillyUnit? = null,
)

/**
 * Holds everything the Trip screen shows. Address text and autocomplete are the trickiest part:
 * each field's typed text lives in its own debounced [MutableStateFlow] so a fast typist doesn't
 * fire an OpenRouteService request per keystroke — see [observeQuery].
 */
class TripViewModel(
    private val repository: TripRepository,
    private val preferencesStore: PreferencesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripUiState())
    val uiState: StateFlow<TripUiState> = _uiState.asStateFlow()

    private val originQuery = MutableStateFlow("")
    private val destinationQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            preferencesStore.unit.collectLatest { unit -> _uiState.update { it.copy(unit = unit) } }
        }
        observeQuery(originQuery, focus = { null }) { suggestions ->
            _uiState.update { it.copy(originSuggestions = suggestions) }
        }
        // Destination suggestions are biased toward wherever the trip starts, so a vague
        // destination ("main st") offers streets near the origin instead of the most globally
        // prominent match. Read lazily rather than captured, because the origin can change
        // after this collector is set up.
        observeQuery(destinationQuery, focus = { _uiState.value.originSelected }) { suggestions ->
            _uiState.update { it.copy(destinationSuggestions = suggestions) }
        }
    }

    /** Debounces typing, drops the request entirely once the field is empty, and always keeps
     * only the latest in-flight lookup. */
    @OptIn(FlowPreview::class)
    private fun observeQuery(
        query: MutableStateFlow<String>,
        focus: () -> Place?,
        onResult: (List<Place>) -> Unit,
    ) {
        viewModelScope.launch {
            query
                .debounce(350)
                .distinctUntilChanged()
                .collectLatest { text ->
                    if (text.isBlank()) {
                        onResult(emptyList())
                        return@collectLatest
                    }
                    // Autocomplete failures stay silent in the UI on purpose — nagging on every
                    // keystroke would be worse than simply showing no suggestions, and Calculate
                    // still reports its own errors properly. Logging keeps them debuggable.
                    val results = runCatching { repository.suggestions(text, focus()) }
                        .onFailure { Log.w(TAG, "Autocomplete failed for \"$text\"", it) }
                        .getOrDefault(emptyList())
                    onResult(results)
                }
        }
    }

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

    fun onSuggestionPicked(field: TripField, place: Place) {
        _uiState.update { state ->
            when (field) {
                TripField.ORIGIN -> state.copy(
                    originQuery = place.label,
                    originSelected = place,
                    originSuggestions = emptyList(),
                    tripResult = null,
                )
                TripField.DESTINATION -> state.copy(
                    destinationQuery = place.label,
                    destinationSelected = place,
                    destinationSuggestions = emptyList(),
                    tripResult = null,
                )
            }
        }
        // Deliberately does *not* push the chosen label into the debounced query flow: doing so
        // would fire a fresh lookup for the very text the user just resolved and pop the
        // suggestion list straight back open underneath the field.
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
                    originSuggestions = emptyList(),
                    destinationSuggestions = emptyList(),
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

                val trip = repository.calculateTrip(origin, destination)
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
