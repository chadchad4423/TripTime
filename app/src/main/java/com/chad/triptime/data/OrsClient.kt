package com.chad.triptime.data

import com.chad.triptime.model.Place
import com.chad.triptime.model.TripResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for any OpenRouteService call that TripTime cannot recover from on its own. */
class OrsException(message: String) : Exception(message)

/** ORS's own error code for a requested route longer than its 6,000 km server limit. Arrives
 * with HTTP 400, so the status alone isn't enough to recognise it. */
private const val ORS_DISTANCE_LIMIT_EXCEEDED = 2004

/**
 * Talks to OpenRouteService (openrouteservice.org) over plain HTTPS. Nothing here depends on
 * Google Play Services, which the Mudita Kompakt's de-Googled AOSP build does not have.
 *
 * The [apiKey] is fixed for the life of the app — it is baked in at build time from
 * local.properties rather than entered by the user (see DECISIONS.md D-005), so this is the
 * only class that needs to know about it and callers never pass one in.
 *
 * All calls are plain synchronous OkHttp requests, moved off the caller's thread with
 * `withContext(Dispatchers.IO)` — TripTime does not need OkHttp's own async callback API for
 * a handful of simple, one-shot requests.
 */
class OrsClient(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Type-ahead suggestions as the user types. [focus] biases results toward a location —
     * TripTime passes the already-chosen starting point when suggesting destinations, so that
     * typing "main st" for a destination offers streets near the origin rather than the most
     * globally prominent match.
     */
    suspend fun autocomplete(query: String, focus: Place?): List<Place> =
        geocode(endpoint = "geocode/autocomplete", query = query, focus = focus, size = 5)

    /** A single best-match lookup, used when the user types an address without picking one
     * of the autocomplete suggestions and then presses Calculate. */
    suspend fun search(query: String, focus: Place?): List<Place> =
        geocode(endpoint = "geocode/search", query = query, focus = focus, size = 1)

    private suspend fun geocode(
        endpoint: String,
        query: String,
        focus: Place?,
        size: Int,
    ): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val urlBuilder = "https://api.openrouteservice.org/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", requireApiKey())
            .addQueryParameter("text", query)
            .addQueryParameter("size", size.toString())
        if (focus != null) {
            urlBuilder
                .addQueryParameter("focus.point.lon", focus.longitude.toString())
                .addQueryParameter("focus.point.lat", focus.latitude.toString())
        }

        val request = Request.Builder().url(urlBuilder.build()).get().build()
        val body = execute(request)
        val parsed = json.decodeFromString(GeocodeResponse.serializer(), body)
        parsed.features.mapNotNull { feature ->
            val coordinates = feature.geometry.coordinates
            if (coordinates.size < 2) return@mapNotNull null
            Place(
                label = feature.properties.label,
                longitude = coordinates[0],
                latitude = coordinates[1],
            )
        }
    }

    /** Driving distance and duration between two already-resolved points. */
    suspend fun drivingDirections(origin: Place, destination: Place): TripResult =
        withContext(Dispatchers.IO) {
            val url = "https://api.openrouteservice.org/v2/directions/driving-car".toHttpUrl()
                .newBuilder()
                .addQueryParameter("api_key", requireApiKey())
                .addQueryParameter("start", "${origin.longitude},${origin.latitude}")
                .addQueryParameter("end", "${destination.longitude},${destination.latitude}")
                .build()

            val request = Request.Builder().url(url).get().build()
            val body = execute(request)
            val parsed = json.decodeFromString(DirectionsResponse.serializer(), body)
            val summary = parsed.features.firstOrNull()?.properties?.summary
                ?: throw OrsException("No driving route found between these locations.")
            TripResult(distanceMeters = summary.distance, durationSeconds = summary.duration)
        }

    /** Only reachable in a build made without `ORS_API_KEY` in local.properties — a mistake by
     * whoever built the app, not something the person holding the phone can fix. */
    private fun requireApiKey(): String {
        if (apiKey.isBlank()) {
            throw OrsException("This build of TripTime has no OpenRouteService key.")
        }
        return apiKey
    }

    private fun execute(request: Request): String {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (io: IOException) {
            throw OrsException("No connection. Check the Kompakt's Wi-Fi or cellular data.")
        }
        response.use {
            val bodyString = it.body?.string().orEmpty()
            if (it.isSuccessful) return bodyString

            // ORS uses two different shapes for `error`: an object for routing failures
            // ({"error":{"code":2010,"message":"…"}}) but a bare string for auth failures
            // ({"error":"Access to this API has been disallowed"}). Only the object form is
            // modelled; the string form fails to decode and falls back to the messages below,
            // which is fine because every status that produces it is handled explicitly.
            val error = runCatching {
                json.decodeFromString(OrsErrorResponse.serializer(), bodyString).error
            }.getOrNull()

            throw OrsException(
                when {
                    // The key is the app's, not the user's, so there is nothing they can do
                    // about this — say so plainly instead of pointing at a settings screen
                    // that no longer exists.
                    it.code == 401 || it.code == 403 ->
                        "TripTime's map service key was rejected. Try again later."

                    // Covers both "no road connects these" (ORS code 2009, e.g. San Diego to
                    // Maui) and "no road near this point" (2010). ORS's own wording for the
                    // latter is a two-line paragraph about a 350 metre radius — far too much
                    // text for the Kompakt's screen, and nothing the user can act on.
                    it.code == 404 -> "No driving route found between these locations."

                    // ORS refuses to route anything over 6,000 km and says so as "Request
                    // parameters exceed the server configuration limits. The approximated route
                    // distance must not be greater than 6000000.0 meters." Say it in plain words.
                    error?.code == ORS_DISTANCE_LIMIT_EXCEEDED ->
                        "That's too far to drive. Routes are limited to about 6,000 km (3,700 mi)."

                    it.code == 429 ->
                        "Too many requests to OpenRouteService — wait a moment and try again."

                    else -> error?.message ?: "OpenRouteService request failed (HTTP ${it.code})."
                }
            )
        }
    }
}
