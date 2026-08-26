package com.chad.triptime.model

/**
 * One resolved location: a human-readable label plus the coordinates OpenRouteService needs
 * for a directions request. TripTime never shows [latitude]/[longitude] to the user directly —
 * they only exist to drive the driving-distance lookup.
 */
data class Place(
    val label: String,
    val latitude: Double,
    val longitude: Double,
)
