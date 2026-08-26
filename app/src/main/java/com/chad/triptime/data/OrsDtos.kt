package com.chad.triptime.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal response models for the two OpenRouteService (openrouteservice.org) endpoints
 * TripTime uses. Both the geocoder (search/autocomplete) and the directions endpoint return
 * GeoJSON; these models only pull out the fields TripTime actually reads and ignore the rest
 * (the OkHttp client configures `ignoreUnknownKeys = true` for exactly that reason).
 *
 * Verified 2026-08-25 against live responses from ORS engine 9.9.0 — `geocode/autocomplete`
 * and `v2/directions/driving-car` both match the fields below exactly.
 *
 * Reference: https://giscience.github.io/openrouteservice/api-reference/
 */

// --- Geocoding (search + autocomplete share this shape) ---

@Serializable
data class GeocodeResponse(
    val features: List<GeocodeFeature> = emptyList(),
)

@Serializable
data class GeocodeFeature(
    val geometry: GeoJsonPoint,
    val properties: GeocodeProperties,
)

@Serializable
data class GeoJsonPoint(
    // GeoJSON order is [longitude, latitude].
    val coordinates: List<Double>,
)

@Serializable
data class GeocodeProperties(
    val label: String,
)

// --- Directions ---

@Serializable
data class DirectionsResponse(
    val features: List<DirectionsFeature> = emptyList(),
)

@Serializable
data class DirectionsFeature(
    val properties: DirectionsProperties,
)

@Serializable
data class DirectionsProperties(
    val summary: DirectionsSummary,
)

@Serializable
data class DirectionsSummary(
    val distance: Double,
    val duration: Double,
)

/**
 * Structured error payload ORS returns for routing failures, e.g.
 * `{"error":{"code":2010,"message":"Could not find routable point…"}}`.
 *
 * Auth failures use a different, unmodelled shape where `error` is a bare string — see the
 * comment in [OrsClient.execute] for why that is safe to leave undecoded.
 */
@Serializable
data class OrsErrorResponse(
    val error: OrsErrorDetail? = null,
)

@Serializable
data class OrsErrorDetail(
    val message: String? = null,
    @SerialName("code") val code: Int? = null,
)
