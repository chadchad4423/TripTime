package com.chad.triptime.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chad.triptime.model.DistanceUnit
import com.chad.triptime.model.SillyUnit
import com.chad.triptime.model.TripResult
import com.chad.triptime.ui.components.PlaceField
import com.chad.triptime.viewmodel.TripField
import com.chad.triptime.viewmodel.TripViewModel
import com.mudita.mmd.components.buttons.ButtonDefaultsMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD

@OptIn(ExperimentalMaterial3Api::class) // TopAppBarMMD wraps M3's experimental TopAppBar
@Composable
fun TripScreen(viewModel: TripViewModel, onOpenPrivacy: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    // Closing the keyboard is part of calculating, not a separate courtesy: the keyboard covers
    // the answer area, so leaving it up would draw the result behind it.
    val calculate = {
        keyboard?.hide()
        viewModel.calculate()
    }

    // Back closes the suggestion list first, keeping what was typed. Without this the list could
    // not be dismissed at all: the first back only hid the keyboard, leaving the list covering the
    // Calculate button, and the second back left the app -- which looked like back had erased the
    // addresses. Hiding the keyboard at the same time is deliberate: one press should clear
    // everything covering the layout, not two.
    // Where the content area begins, so a suggestion list that grows upward stops at the
    // header rather than riding over it and the status bar.
    var contentTopPx by remember { mutableIntStateOf(0) }

    val suggestionsShowing = state.originSuggestions.isNotEmpty() ||
        state.destinationSuggestions.isNotEmpty()

    // The list belongs to typing, so it goes when the keyboard goes. Without this it outlived the
    // keyboard and sat over the Calculate button, and since the first back press is swallowed by
    // the IME it took a second press to clear -- which, before the list could be dismissed at all,
    // was the press that left the app and appeared to wipe the typed addresses.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible) viewModel.dismissSuggestions()
    }

    // Kept as well, for the case where back reaches the app rather than the keyboard: dismissing
    // the list should never be the same gesture as leaving the app.
    BackHandler(enabled = suggestionsShowing) {
        keyboard?.hide()
        viewModel.dismissSuggestions()
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBarMMD(
                    title = { TextMMD("TripTime") },
                    // The unit choice lives here rather than as a switch in the body: it is a
                    // rarely-touched preference, not part of the main task, and the top bar's
                    // action slot is where secondary controls belong.
                    actions = { UnitToggle(unit = state.unit, onSelect = viewModel::selectUnit) },
                    // MMD's own top-bar rule draws about 1px, even though the library's own
                    // TopAppBarDefaultsMMD.dividerLineHeight is 3.dp. Turn it off and draw the
                    // rule with HorizontalDividerMMD, which does honour the 3.dp spec.
                    showDivider = false,
                )
                HorizontalDividerMMD()
            }
        },
        // Deliberately no bottomBar and no imePadding anywhere on this screen. An e-ink layout
        // should not rearrange itself, and a Calculate button that floats above the keyboard and
        // then drops to the bottom when it closes is exactly that. Instead every laid-out
        // element has one fixed position: the button sits directly under "To", high enough to
        // clear the keyboard, and the keyboard only ever covers the answer area below it —
        // which is empty while you are still typing.
        //
        // The budget here is tighter than the emulator suggests, and was measured on real
        // hardware: MuditaOS's status bar is taller than the emulator's *and* its keyboard has
        // an extra punctuation row, leaving the top of the keyboard at y=450 of 800 — about
        // 240dp of usable height below the header, not the ~350dp a stock emulator implies.
        // The spacing below is sized so the button clears that line. If anything is ever added
        // above the button, re-measure on device rather than trusting a preview.
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { contentTopPx = it.positionInWindow().y.toInt() },
        ) {
            // These three spacers were trimmed (8/12/8 dp -> 4/8/6 dp) to pay for the taller
            // Calculate button below without moving its bottom edge down into the keyboard.
            Spacer(Modifier.height(4.dp))

            PlaceField(
                label = "From",
                value = state.originQuery,
                suggestions = state.originSuggestions,
                onValueChange = { viewModel.onQueryChange(TripField.ORIGIN, it) },
                onSuggestionPicked = { viewModel.onSuggestionPicked(TripField.ORIGIN, it) },
                contentTopPx = contentTopPx,
            )

            Spacer(Modifier.height(8.dp))

            PlaceField(
                label = "To",
                value = state.destinationQuery,
                suggestions = state.destinationSuggestions,
                onValueChange = { viewModel.onQueryChange(TripField.DESTINATION, it) },
                onSuggestionPicked = { viewModel.onSuggestionPicked(TripField.DESTINATION, it) },
                contentTopPx = contentTopPx,
                // The destination is the last thing typed, so the keyboard's action key is "Go"
                // and runs the same calculation as the button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { calculate() }),
            )

            Spacer(Modifier.height(6.dp))

            // 48.dp tall rather than ButtonMMD's natural ~38.dp, and the label 20.sp bold rather
            // than the 16.sp regular default. At the default weight the white label was thin
            // enough to half-disappear into the black fill on the e-ink panel — legible on an
            // LCD, not here. The extra height is reclaimed from the spacers above rather than
            // taken from below, because the button's bottom edge has only 17px of clearance over
            // the keyboard (whose top edge is y=450 of 800) and must not grow into it.
            //
            // While a trip is calculating the button inverts — white fill, black label, black
            // outline — and the label keeps saying "Calculate" (DECISIONS.md D-016). Swapping it
            // to "Calculating…" meant a word changing under the user's thumb, which on e-ink is a
            // slow partial refresh of a dozen small glyph areas and reads as a smear rather than
            // as feedback. A whole-button tone flip is one large, clean change the panel renders
            // well, and leaving the word alone means there is nothing to re-read.
            //
            // Two details that matter more than they look:
            //  - `border` is passed unconditionally, not only while loading. Black-on-black is
            //    invisible in the resting state, and passing it always means both states lay out
            //    identically — no border appearing the instant you tap, which would be a D-007
            //    violation in the one place the layout is guaranteed to be under a thumb.
            //  - The disabled colours are spelled out rather than inherited, and they are MMD's
            //    own two tokens swapped rather than hand-picked black and white. MMD disables a
            //    button by dropping fill and label to alpha 0.75 (ButtonDefaultsMMD.buttonColors),
            //    which is a dithered grey here and exactly what AGENTS.md's "no grey, no reduced
            //    alpha" rule exists to prevent. Swapping the pair instead lands on MMD's *other*
            //    button style: OutlinedButtonMMD is a transparent fill, a black label and a
            //    BorderStroke(ButtonDefaultsMMD.borderWidth, Color.Black) over the same 8.dp
            //    corner radius — so the busy state is a component the library already ships, not
            //    a look invented for this app.
            ButtonMMD(
                onClick = calculate,
                enabled = !state.isLoading,
                border = BorderStroke(
                    ButtonDefaultsMMD.borderWidth,
                    MaterialTheme.colorScheme.primaryContainer,
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    // The inversion, stated as an inversion.
                    disabledContainerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    // The visible label no longer changes, so the progress state is carried
                    // explicitly for screen readers rather than being lost along with the word.
                    .semantics {
                        stateDescription = if (state.isLoading) "Calculating" else "Ready"
                    },
            ) {
                TextMMD(
                    text = "Calculate",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    // A little tracking: on e-ink, white strokes on a black fill bleed outward,
                    // and letters set tight read as a smudge at a glance.
                    letterSpacing = 0.5.sp,
                )
            }

            // Everything below the button is the answer. It takes whatever space is left and
            // centres in it. While the keyboard is open this is the region it covers, which is
            // why nothing that needs tapping lives here.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.errorMessage != null -> Text(
                        text = state.errorMessage!!,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Black)
                            .padding(12.dp),
                    )
                    state.tripResult != null ->
                        TripResultPanel(
                            result = state.tripResult!!,
                            unit = state.unit,
                            sillyUnit = state.sillyUnit,
                        )

                    // Last in the order deliberately: a message from remote config (D-020) is the
                    // least urgent thing this area can hold, so an error or an actual answer
                    // always wins. It lives here rather than in a bar of its own because this
                    // region is already dynamic — nothing in the static layout moves to make room
                    // for it, which is what D-007 requires.
                    state.notice != null -> TextMMD(
                        text = state.notice!!,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            // Pinned below the answer area rather than in the top bar, which is already carrying
            // the unit control. It sits in the region the keyboard covers, which is fine — it is
            // never needed mid-typing — and like everything else here it never moves.
            TextMMD(
                text = "Privacy",
                fontSize = 14.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(onClick = onOpenPrivacy)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Miles / kilometres / "us", as small tappable labels in the top bar. The selected unit is bold
 * and underlined rather than tinted — on a monochrome panel, weight and rule are the only
 * "selected" signals that survive.
 *
 * "us" is the joke unit: the distance in Ram 2500s, blue whales or Panama Canals, per
 * [com.chad.triptime.model.SillyUnits].
 */
@Composable
private fun UnitToggle(unit: DistanceUnit, onSelect: (DistanceUnit) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        UnitLabel("mi", selected = unit == DistanceUnit.IMPERIAL) { onSelect(DistanceUnit.IMPERIAL) }
        UnitSeparator()
        UnitLabel("km", selected = unit == DistanceUnit.METRIC) { onSelect(DistanceUnit.METRIC) }
        UnitSeparator()
        UnitLabel("us", selected = unit == DistanceUnit.SILLY) { onSelect(DistanceUnit.SILLY) }
    }
}

@Composable
private fun UnitSeparator() {
    TextMMD(text = "/", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun UnitLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    TextMMD(
        text = text,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/**
 * The answer TripTime exists to give. Driving time is the headline and is set as large as the
 * Kompakt's 361 dp-wide screen allows; the distance sits underneath as a supporting fact.
 */
@Composable
private fun TripResultPanel(result: TripResult, unit: DistanceUnit, sillyUnit: SillyUnit?) {
    val spoken = "About ${result.formatDuration()} driving, ${result.formatDistance(unit, sillyUnit)}"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            // The headline is split into separate number and unit runs for typesetting, which
            // would otherwise be read out piecemeal. Announce the whole panel as one phrase.
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        TextMMD(
            text = "DRIVING TIME",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(12.dp))
        DurationHeadline(result)
        Spacer(Modifier.height(16.dp))
        TextMMD(
            text = result.formatDistance(unit, sillyUnit),
            fontSize = 22.sp,
        )
    }
}

/**
 * Renders e.g. "1 hr 41 min" with the numerals large and the units small, so the number the
 * user actually came for carries the visual weight. Splitting hours and minutes this way also
 * keeps the longest realistic value inside the screen width — a single run of text at this size
 * would overflow 361 dp well before it ran out of plausible trip durations.
 */
@Composable
private fun DurationHeadline(result: TripResult) {
    val numberSize = 68.sp
    val unitSize = 24.sp

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (result.hoursPart > 0) {
            TextMMD(text = "${result.hoursPart}", fontSize = numberSize, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            TextMMD(text = "hr", fontSize = unitSize, modifier = Modifier.padding(bottom = 10.dp))
            Spacer(Modifier.width(12.dp))
        }
        // A trip of exactly N hours shows just "N hr"; anything else shows the minutes, including
        // the sub-hour case where minutes are the only thing to show.
        if (result.hoursPart == 0 || result.minutesPart > 0) {
            TextMMD(text = "${result.minutesPart}", fontSize = numberSize, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            TextMMD(text = "min", fontSize = unitSize, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}
