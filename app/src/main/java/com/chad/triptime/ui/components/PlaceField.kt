package com.chad.triptime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import kotlin.math.floor

/**
 * Height of one suggestion row: 12.dp padding above and below a single line of bodyLarge.
 * Measured on the Kompakt at 65px, i.e. 48.8.dp at 213dpi, and rounded. It is a constant rather
 * than a measurement because the count of rows that fit has to be known before the first frame is
 * drawn -- measuring a row first would show an over-long list and then snap it shorter, and a list
 * that resizes under the reader is exactly what an e-ink layout must not do. Re-measure if the row
 * padding or the text style ever changes.
 */
private val SUGGESTION_ROW_HEIGHT = 49.dp

/** Breathing room between the last suggestion and the top of the keyboard. */
private val KEYBOARD_CLEARANCE = 8.dp

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
    /**
     * Top of the screen's content area in window coordinates, below the app bar. The list may grow
     * upward into the space above the field, but not past this -- otherwise it rides over the
     * header and the status bar, which looks like the app has come apart.
     */
    contentTopPx: Int = 0,
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
    // Where the field sits in the window, and how tall the window is, so the suggestion list can
    // be limited to the space that actually exists between the field and the keyboard.
    var fieldTopInWindow by remember { mutableIntStateOf(0) }
    var rootHeightPx by remember { mutableIntStateOf(0) }
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
                        fieldTopInWindow = it.positionInWindow().y.toInt()
                        rootHeightPx = it.findRootCoordinates().size.height
                    },
            )

            // How many suggestions fit between the bottom of this field and the top of the
            // keyboard. The list is a Popup, so nothing stops it drawing straight over the
            // keyboard -- and it did: on the "To" field it buried three rows of keys, and on
            // "From" it covered the QWERTY row, so the user could not see what they were typing
            // on. Whole rows only: a row sliced through its own text reads as damage, which is
            // the same reason the rows are single-line in the first place.
            val imeHeightPx = WindowInsets.ime.getBottom(density)
            val clearancePx = with(density) { KEYBOARD_CLEARANCE.toPx() }
            val rowPx = with(density) { SUGGESTION_ROW_HEIGHT.toPx() }
            val belowPx = rootHeightPx - imeHeightPx - (fieldTopInWindow + slotHeightPx) - clearancePx
            // Space above the field, which on the "To" field is occupied by the already-filled
            // "From" field -- worth covering, since the user has finished with it, and better than
            // the single row that fits below it once the keyboard is up. No clearance subtracted
            // here: KEYBOARD_CLEARANCE exists to keep the list off the keyboard, and the keyboard
            // is only ever below. Subtracting it up here cost a whole row -- measured on the
            // device, the second row missed by 0.13 of a pixel.
            val abovePx = (fieldTopInWindow - contentTopPx).toFloat()
            val rowsBelow = floor(belowPx / rowPx).toInt()
            val rowsAbove = floor(abovePx / rowPx).toInt()
            // Below unless above genuinely fits more, so the list stays in the expected place
            // whenever it can. Before the first measurement, and whenever the keyboard is down,
            // there is nothing to avoid: show everything.
            val unmeasured = rootHeightPx == 0 || imeHeightPx == 0
            val flipAbove = !unmeasured && rowsAbove > rowsBelow
            val roomForRows = when {
                unmeasured -> suggestions.size
                flipAbove -> rowsAbove
                else -> rowsBelow
            }
            val visible = suggestions.take(roomForRows.coerceAtLeast(1))
            val listHeightPx = (visible.size * rowPx).toInt()

            if (visible.isNotEmpty() && fieldSize != IntSize.Zero) {
                Popup(
                    // Aligned to the field's top-left, then pushed down by the slot height so the
                    // list hangs directly beneath it and stays put on focus change -- or lifted by
                    // its own height so it sits directly above, when that is where the room is.
                    offset = IntOffset(0, if (flipAbove) -listHeightPx else slotHeightPx),
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
                        visible.forEachIndexed { index, place ->
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
                            if (index != visible.lastIndex) {
                                HorizontalDivider(color = Color.Black, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
