package com.chad.triptime

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chad.triptime.ui.PrivacyScreen
import com.chad.triptime.ui.TripScreen
import com.chad.triptime.viewmodel.TripViewModel
import com.mudita.mmd.ThemeMMD

/** TripTime is one working screen plus a static privacy page, so a navigation library would be
 * more machinery than the app needs — this is a plain in-memory toggle. See DECISIONS.md D-004. */
private enum class Screen { TRIP, PRIVACY }

@Composable
fun TripTimeApp(container: AppContainer) {
    var screen by remember { mutableStateOf(Screen.TRIP) }

    ThemeMMD {
        // System-bar insets are handled inside each screen's Scaffold — TopAppBarMMD consumes the
        // status bar itself, so padding here as well would inset it twice.
        when (screen) {
            Screen.TRIP -> {
                val viewModel: TripViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { TripViewModel(container.tripRepository, container.preferencesStore) }
                    }
                )
                TripScreen(
                    viewModel = viewModel,
                    onOpenPrivacy = { screen = Screen.PRIVACY },
                )
            }
            Screen.PRIVACY -> {
                // Without this the system back gesture would leave the app entirely rather than
                // returning to the trip screen, since there is no real back stack to pop.
                BackHandler { screen = Screen.TRIP }
                PrivacyScreen(onDone = { screen = Screen.TRIP })
            }
        }
    }
}
