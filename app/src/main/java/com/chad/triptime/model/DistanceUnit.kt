package com.chad.triptime.model

/**
 * The three ways TripTime can present a trip's distance. Kept as an enum (rather than a raw
 * boolean) so the UI toggle, DataStore persistence, and formatting code all read the same
 * explicit names.
 */
enum class DistanceUnit {
    METRIC,
    IMPERIAL,

    /** Distance in whatever object of known length [SillyUnits] picked for this trip. */
    SILLY,
}
