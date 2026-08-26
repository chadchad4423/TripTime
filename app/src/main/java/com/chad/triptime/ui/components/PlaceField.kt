package com.chad.triptime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.chad.triptime.model.Place
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD

/**
 * A labeled address field with an OpenRouteService autocomplete dropdown underneath it. Used
 * for both the "From" and "To" fields on [com.chad.triptime.ui.TripScreen] — same behavior,
 * different label and callbacks, so it only needs to exist once.
 *
 * The suggestion list is a [Popup] drawn *over* the content below rather than an in-flow
 * sibling. In flow it took real layout space, and with the keyboard open there is only about
 * 175 dp of content area on the Kompakt — not enough for a field, five suggestions and the
 * second field, so the list was clipped and the "To" field got pushed off screen entirely.
 *
 * Suggestions render as a plain bordered list rather than `LazyColumnMMD`: OpenRouteService is
 * asked for at most 5 results (see `OrsClient.autocomplete`), so there is never enough content
 * to need lazy layout.
 */
@Composable
fun PlaceField(
    label: String,
    value: String,
    suggestions: List<Place>,
    onValueChange: (String) -> Unit,
    onSuggestionPicked: (Place) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // TextFieldMMD is 2px taller while focused — its focused indicator line is thicker — and
    // that difference would otherwise push the Calculate button and the whole answer area down
    // every time a field gained focus. This slot only ever grows, so after a field has been
    // focused once the space it occupies is fixed and nothing below it can shift again.
    // Measured rather than hard-coded so it still holds if the system font scale changes.
    var slotHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth()) {
        // A plain label above the field, deliberately *not* TextFieldMMD's built-in `label`.
        // A floating label animates both its position and its type size as the field gains and
        // loses focus, which is exactly the kind of movement an e-ink layout must not do. This
        // one is the same size in the same place at all times.
        TextMMD(text = label, fontSize = 14.sp)

        Spacer(Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = with(density) { slotHeightPx.toDp() }),
        ) {
            TextFieldMMD(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        fieldSize = it.size
                        if (it.size.height > slotHeightPx) slotHeightPx = it.size.height
                    },
            )

            if (suggestions.isNotEmpty() && fieldSize != IntSize.Zero) {
                Popup(
                    // Aligned to the field's top-left, then pushed straight down by the slot
                    // height so the list hangs directly beneath it, and stays put on focus change.
                    offset = IntOffset(0, slotHeightPx),
                    // Not focusable: the keyboard must stay up and keep receiving keystrokes while
                    // the list is showing, since the list updates as the user keeps typing.
                    properties = PopupProperties(focusable = false),
                ) {
                    Column(
                        modifier = Modifier
                            .width(with(density) { fieldSize.width.toDp() })
                            // A backstop only. Since every row is one line (see maxLines below),
                            // five results come to about 180.dp and can never reach this.
                            .heightIn(max = 300.dp)
                            .background(Color.White)
                            .border(width = 1.dp, color = Color.Black),
                    ) {
                        suggestions.forEachIndexed { index, place ->
                            Text(
                                text = place.label,
                                style = MaterialTheme.typography.bodyLarge,
                                // One line per result, always. Wrapping to two lines meant five
                                // results could exceed the popup's ceiling, and the fifth row got
                                // sliced through the middle of its own text — a half-rendered row
                                // reads as damage, and is worse than a row that is honestly cut
                                // short. Fixed-height rows also make the popup a predictable size
                                // no matter what the geocoder returns, which is what an e-ink
                                // overlay wants. The cost is real: long labels lose their tail,
                                // and ORS labels routinely run 60-90 characters, so two results
                                // that differ only in their suffix can now look identical.
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSuggestionPicked(place) }
                                    .padding(12.dp),
                            )
                            if (index != suggestions.lastIndex) {
                                HorizontalDivider(color = Color.Black, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
