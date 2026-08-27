package com.chad.triptime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chad.triptime.model.Place
import com.chad.triptime.viewmodel.PickerState
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

/**
 * A whole screen for choosing an address, replacing the dropdown that used to hang under the
 * field (DECISIONS.md D-022).
 *
 * The dropdown was a Popup, so it drew over everything below it -- including the keyboard, which
 * it had to be measured against and sized around. That arithmetic worked, but it left barely two
 * results visible on the lower field and would need re-tuning for any other keyboard height or
 * font scale. A screen has no such coupling: there is no keyboard while it is open, so every
 * result fits, at full width, with MMD's own scrollbar if there are ever more than fit.
 *
 * Nothing is looked up until the user asks for it, so results can no longer arrive mid-word.
 */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBarMMD wraps M3's experimental TopAppBar
@Composable
fun PlacePickerScreen(
    picker: PickerState,
    onPick: (Place) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)

    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBarMMD(
                    title = { TextMMD("Choose an address") },
                    actions = {
                        TextMMD(
                            text = "Cancel",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(onClick = onCancel)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                    // See TripScreen: MMD's built-in rule renders ~1px, so draw the 3.dp one.
                    showDivider = false,
                )
                HorizontalDividerMMD()
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // What was searched for, so the reader can tell a thin result list from a typo
            // without going back to check.
            TextMMD(text = "Results for", fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            TextMMD(text = picker.query, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            HorizontalDividerMMD()

            when {
                // Static text, not a spinner: an animated indicator on e-ink is a stream of
                // partial repaints, which is what ghosts (AGENTS.md).
                picker.isLoading -> PickerMessage("Searching\u2026")
                picker.errorMessage != null -> PickerMessage(picker.errorMessage)
                picker.results.isEmpty() -> PickerMessage(
                    "No addresses matched. Go back and try a fuller address \u2014 a street with a " +
                        "town, or a town with a state."
                )
                else -> PickerResults(results = picker.results, onPick = onPick)
            }
        }
    }
}

/** One line of explanation where the results would be: searching, empty, or failed. */
@Composable
private fun PickerMessage(text: String) {
    Spacer(Modifier.height(20.dp))
    TextMMD(text = text, fontSize = 15.sp, lineHeight = 21.sp)
}

/**
 * The results themselves, one per row.
 *
 * `LazyColumnMMD` rather than a plain column: it brings MMD's e-ink scrollbar -- a track, a thumb
 * showing position, and up/down arrows that advance by whole items -- which is the paging model
 * this panel wants. `scrollStep = 1` for the same reason the privacy page used to: one row per
 * tap can never skip an unread result.
 *
 * Rows wrap to as many lines as they need. The old dropdown forced every row onto one line with an
 * ellipsis, because five wrapped rows would have overflowed a popup that was already fighting the
 * keyboard for space -- and OpenRouteService labels routinely run 60-90 characters, so two results
 * differing only in their suffix looked identical. With a screen to work in, that compromise goes.
 */
@Composable
private fun PickerResults(results: List<Place>, onPick: (Place) -> Unit) {
    LazyColumnMMD(
        modifier = Modifier.fillMaxSize(),
        isScrollbarVisible = true,
        scrollStep = 1,
    ) {
        items(results) { place ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(place) },
            ) {
                TextMMD(
                    text = place.label,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(vertical = 14.dp, horizontal = 2.dp),
                )
                HorizontalDividerMMD()
            }
        }
    }
}
