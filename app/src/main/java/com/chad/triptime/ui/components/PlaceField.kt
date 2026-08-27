package com.chad.triptime.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD

/**
 * A labeled address field. Used for both "From" and "To" on
 * [com.chad.triptime.ui.TripScreen] -- same behavior, different label and callbacks.
 *
 * It used to carry an autocomplete dropdown as well, drawn as a `Popup` over the content below.
 * That is gone: choosing an address now happens on its own screen
 * ([com.chad.triptime.ui.PlacePickerScreen]), reached from the keyboard's search key. See
 * DECISIONS.md D-022 for why -- briefly, the dropdown had to be measured against the keyboard it
 * was covering, and no arrangement of it left room for more than a couple of results on the lower
 * field. What is left here is a text field and a label, with nothing overlaying anything.
 */
@Composable
fun PlaceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    // TextFieldMMD is 2px taller while focused -- its focused indicator line is thicker -- and
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
                        if (it.size.height > slotHeightPx) slotHeightPx = it.size.height
                    },
            )
        }
    }
}
