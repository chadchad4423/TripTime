package com.chad.triptime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chad.triptime.BuildConfig
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A plain statement of what TripTime does and does not do with the user's data.
 *
 * Everything on this page is a claim about the code that has to stay true: if TripTime ever
 * gains analytics, a saved trip history, a location permission, or a third network call, this
 * text is wrong and must be updated in the same change — and the version marker at the foot of
 * the page bumped with it, since that marker is what tells a reader the notice has changed. It
 * is deliberately specific about which two requests leave the phone rather than hiding behind
 * "we may collect certain information".
 */

/**
 * The version of the notice *text*, which is not the app's version and does not move with it.
 * It exists so a reader who has seen this page before can tell at a glance whether it changed;
 * bump it whenever [PRIVACY_BLOCKS] is edited **after v1 has actually shipped to a real
 * reader**. Editing the wording before then — as happened before the first public build, when
 * the shared-key paragraph was rewritten — doesn't need a bump: there is no prior reader for
 * "v1" to have signalled a change to yet. The app version beside it comes from `BuildConfig`,
 * so that half can never drift out of date on its own.
 */
private const val NOTICE_VERSION = "v2"

private sealed interface Block {
    @JvmInline value class Heading(val text: String) : Block

    @JvmInline value class Paragraph(val text: String) : Block
}

/**
 * The page content as discrete blocks rather than one long string, because [LazyColumnMMD]
 * advances the page by a whole number of *items*. One block per tap-step means a page turn can
 * never land mid-paragraph or skip past text the reader hasn't seen.
 */
private val PRIVACY_BLOCKS: List<Block> = listOf(
    Block.Paragraph(
        "TripTime collects nothing about you. There is no analytics, no crash reporting, no " +
            "advertising, no account, and no identifier of any kind. Nothing you do in this app " +
            "is recorded or reported anywhere."
    ),
    Block.Heading("What leaves your phone"),
    Block.Paragraph(
        "TripTime sends two kinds of request to OpenRouteService, the mapping service it uses, " +
            "both over an encrypted HTTPS connection:"
    ),
    Block.Paragraph(
        "1. Address lookup. As you type in \"From\" or \"To\", the text you have typed so far is " +
            "sent so that matching addresses can be suggested back. Once a starting point is " +
            "chosen, destination lookups also include that starting point's coordinates, which " +
            "is what makes nearby results rank higher."
    ),
    Block.Paragraph(
        "2. Driving distance and time. When you press Calculate, the coordinates of the two " +
            "places you chose are sent, and the driving distance and duration come back."
    ),
    Block.Paragraph(
        "That is everything. No name, no phone number, no device identifier, and no record of " +
            "trips you looked up previously is ever transmitted."
    ),
    Block.Paragraph(
        "One thing the list above cannot cover: because your phone contacts OpenRouteService " +
            "directly, their servers can see your device's IP address, the same way any " +
            "website you visit can. TripTime does not send it, read it, or store it — it " +
            "is a property of the connection itself, not something the app chooses to " +
            "include."
    ),
    Block.Heading("No location access"),
    Block.Paragraph(
        "TripTime does not ask for location permission and never reads your phone's GPS. It " +
            "cannot tell where you are. Destination suggestions are ranked using the starting " +
            "address you typed, not your actual position."
    ),
    Block.Heading("What is stored on your phone"),
    Block.Paragraph(
        "Only whether you prefer miles, kilometres, or the freedom units. Trips themselves are " +
            "not saved — close the app and they are gone."
    ),
    Block.Heading("OpenRouteService"),
    Block.Paragraph(
        "The requests above are answered by OpenRouteService, operated by HeiGIT gGmbH in " +
            "Heidelberg, Germany. What they log, and for how long, is governed by their own " +
            "privacy policy at openrouteservice.org/privacy-policy — not by this app."
    ),
    Block.Paragraph(
        "If you installed TripTime as a ready-built app rather than building it yourself, your " +
            "copy shares one community access key with everyone else who installed it the same " +
            "way. That key has a limited daily quota. If the day's requests run out, trips stop " +
            "calculating for everyone sharing it until the quota resets \u2014 there is nothing " +
            "wrong with your installation, and nothing personal is exposed by this, but it is " +
            "not a private key. Building TripTime yourself from source with your own free " +
            "OpenRouteService key (see the project's README) avoids this entirely."
    ),
)

private val PAGE_OVERLAP = 44.dp
private val RAIL_WIDTH = 30.dp
private val RAIL_ARROW_HEIGHT = 52.dp

@OptIn(ExperimentalMaterial3Api::class) // TopAppBarMMD wraps M3's experimental TopAppBar
@Composable
fun PrivacyScreen(onDone: () -> Unit) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var viewportPx by remember { mutableIntStateOf(0) }
    val overlapPx = remember(density) { with(density) { PAGE_OVERLAP.toPx() }.roundToInt() }
    val stepPx = (viewportPx - overlapPx).coerceAtLeast(1)

    val maxScroll = scrollState.maxValue
    val atTop = scrollState.value <= 0
    val atBottom = scrollState.value >= maxScroll

    fun turn(delta: Int) {
        scope.launch { scrollState.scrollTo((scrollState.value + delta).coerceIn(0, maxScroll)) }
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            Column {
                TopAppBarMMD(
                    title = { TextMMD("Privacy") },
                    actions = {
                        TextMMD(
                            text = "Done",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(onClick = onDone)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    },
                    showDivider = false,
                )
                HorizontalDividerMMD()
            }
        },
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 16.dp, end = 12.dp)
                    .onSizeChanged { viewportPx = it.height }
                    .verticalScroll(scrollState),
            ) {
                Spacer(Modifier.height(16.dp))

                PRIVACY_BLOCKS.forEach { block ->
                    when (block) {
                        is Block.Heading -> {
                            Spacer(Modifier.height(20.dp))
                            TextMMD(
                                text = block.text,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        is Block.Paragraph -> {
                            TextMMD(text = block.text, fontSize = 15.sp, lineHeight = 22.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                TextMMD(
                    text = "TripTime ${BuildConfig.VERSION_NAME} \u00B7 Notice $NOTICE_VERSION",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(24.dp))
            }

            PageScrollbar(
                position = scrollState.value,
                maxPosition = maxScroll,
                viewportPx = viewportPx,
                canGoUp = !atTop,
                canGoDown = !atBottom,
                onUp = { turn(-stepPx) },
                onDown = { turn(stepPx) },
            )
        }
    }
}

@Composable
private fun PageScrollbar(
    position: Int,
    maxPosition: Int,
    viewportPx: Int,
    canGoUp: Boolean,
    canGoDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier.fillMaxHeight().width(RAIL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailArrow(glyph = "\u25B2", hollow = "\u25B3", label = "Previous page", enabled = canGoUp, onClick = onUp)

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .width(10.dp)
                .border(2.dp, Color.Black),
        ) {
            val trackPx = with(density) { maxHeight.toPx() }
            val minThumbPx = with(density) { 24.dp.toPx() }
            val contentPx = (viewportPx + maxPosition).toFloat()
            val fraction = if (contentPx <= 0f) 1f else (viewportPx / contentPx).coerceIn(0f, 1f)
            val thumbPx = (trackPx * fraction).coerceIn(minThumbPx.coerceAtMost(trackPx), trackPx)
            val travel = trackPx - thumbPx
            val progress = if (maxPosition <= 0) 0f else position.toFloat() / maxPosition
            val offsetPx = (travel * progress.coerceIn(0f, 1f)).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, offsetPx) }
                    .fillMaxWidth()
                    .height(with(density) { thumbPx.toDp() })
                    .background(Color.Black),
            )
        }

        RailArrow(glyph = "\u25BC", hollow = "\u25BD", label = "Next page", enabled = canGoDown, onClick = onDown)
    }
}

@Composable
private fun RailArrow(
    glyph: String,
    hollow: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RAIL_ARROW_HEIGHT)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(text = if (enabled) glyph else hollow, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
