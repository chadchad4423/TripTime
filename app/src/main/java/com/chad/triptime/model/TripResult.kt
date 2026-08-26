package com.chad.triptime.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * A calculated driving distance and duration between two [Place]s, as returned by
 * OpenRouteService. Distance and duration are always stored in the metric units the API
 * returns (meters, seconds); [formatDistance] and [formatDuration] convert for display only,
 * so switching the unit toggle never needs a second network call.
 */
data class TripResult(
    val distanceMeters: Double,
    val durationSeconds: Double,
) {
    /**
     * [sillyUnit] is only read when [unit] is [DistanceUnit.SILLY]. It is passed in rather than
     * picked here because the choice is random: it has to be made once when the trip is
     * calculated and then held, or the text would change every time the screen recomposed.
     */
    fun formatDistance(unit: DistanceUnit, sillyUnit: SillyUnit? = null): String = when (unit) {
        DistanceUnit.METRIC -> {
            val km = distanceMeters / 1000.0
            String.format(Locale.US, "%,.1f km", km)
        }
        DistanceUnit.IMPERIAL -> {
            val miles = distanceMeters / 1609.344
            String.format(Locale.US, "%,.1f mi", miles)
        }
        DistanceUnit.SILLY -> {
            // A trip calculated before the unit existed, or state restored without one; showing
            // miles beats showing nothing.
            if (sillyUnit == null) formatDistance(DistanceUnit.IMPERIAL) else {
                val count = distanceMeters / sillyUnit.meters
                // Whole numbers above 10 (which is the normal case — SillyUnits.pick aims for
                // that band); a decimal only in the fallback case where the count is small.
                val formatted =
                    if (count >= 10) String.format(Locale.US, "%,d", count.roundToInt())
                    else String.format(Locale.US, "%.1f", count)
                "$formatted ${sillyUnit.name}"
            }
        }
    }

    /** Whole minutes, rounded — the screen never shows seconds. */
    val totalMinutes: Int get() = (durationSeconds / 60.0).roundToInt()

    /** Hour and minute parts, exposed separately so the Trip screen can typeset the numbers
     * large and the "hr"/"min" units small (see `DurationHeadline`). [minutesPart] is the
     * remainder within the hour, not the total. */
    val hoursPart: Int get() = totalMinutes / 60
    val minutesPart: Int get() = totalMinutes % 60

    /** Single-line form, used for the spoken/accessibility description of the headline. */
    fun formatDuration(): String = when {
        hoursPart <= 0 -> "$minutesPart min"
        minutesPart == 0 -> "$hoursPart hr"
        else -> "$hoursPart hr $minutesPart min"
    }
}
