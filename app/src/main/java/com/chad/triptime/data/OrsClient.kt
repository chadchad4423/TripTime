package com.chad.triptime.data

import android.util.Log
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

private const val TAG = "OrsClient"

/**
 * Thrown for any OpenRouteService call that TripTime cannot recover from on its own.
 *
 * [retryable] marks the failures that might be cured by switching to the reserve endpoint or key
 * from remote config (D-020) — a dead host, a rejected key, a path that no longer exists. It is
 * deliberately false for answers the service gave on purpose: "no route between these points" is
 * a real answer, and retrying it against a different host would waste quota and change nothing.
 */
class OrsException(message: String, val retryable: Boolean = false) : Exception(message)

/** ORS's own error code for a requested route longer than its 6,000 km server limit. Arrives
 * with HTTP 400, so the status alone isn't enough to recognise it. */
private const val ORS_DISTANCE_LIMIT_EXCEEDED = 2004

/**
 * Where requests go is no longer fixed at build time: [AppConfig] supplies the host and the three
 * endpoint paths, defaulting to the compiled-in HeiGIT values and overridable at runtime by the
 * remote config (DECISIONS.md D-020). D-018 is why — the previous host was retired with paths
 * changing too, and every installed copy had to be replaced by hand to follow it.
 */

/**
 * Talks to OpenRouteService, now hosted on HeiGIT's unified API (api.heigit.org), over
 * plain HTTPS. Nothing here depends on
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
class OrsClient(private val configStore: RemoteConfigStore) {

    /** Read per request, so a config that arrives mid-session takes effect immediately. */
    private val config: AppConfig get() = configStore.current

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
        geocode(path = { it.autocompletePath }, query = query, focus = focus, size = 5)

    /** A single best-match lookup, used when the user types an address without picking one
     * of the autocomplete suggestions and then presses Calculate. */
    suspend fun search(query: String, focus: Place?): List<Place> =
        geocode(path = { it.searchPath }, query = query, focus = focus, size = 1)

    /**
     * Autocomplete and search differ only in which path they use and how many results they want.
     * The path arrives as a lambda over [AppConfig], not as a string, so that a retry after
     * [withEndpointFailover] promotes the reserve config re-reads *both* the host and the path.
     * D-018 moved both at once, so capturing either one early would half-fix the next migration.
     */
    private suspend fun geocode(
        path: (AppConfig) -> String,
        query: String,
        focus: Place?,
        size: Int,
    ): List<Place> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        withEndpointFailover {
            val urlBuilder = (config.apiBase + path(config)).toHttpUrl().newBuilder()
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
    }

    /** Driving distance and duration between two already-resolved points. */
    suspend fun drivingDirections(origin: Place, destination: Place): TripResult =
        withContext(Dispatchers.IO) {
            withEndpointFailover {
                val url = (config.apiBase + config.directionsPath).toHttpUrl()
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
        }

    /** Only reachable in a build made without `ORS_API_KEY` in local.properties — a mistake by
     * whoever built the app, not something the person holding the phone can fix. */
    private fun requireApiKey(): String {
        val apiKey = config.apiKey
        if (apiKey.isBlank()) {
            throw OrsException("This build of TripTime has no OpenRouteService key.")
        }
        return apiKey
    }

    /**
     * Runs [block], and if it fails in a way a different endpoint or key might fix, promotes the
     * reserve config and runs it exactly once more. The retry is transparent: the user sees a
     * slightly slower answer rather than an error, which is the point of holding the override back
     * until it is needed (D-020).
     */
    private fun <T> withEndpointFailover(block: () -> T): T = try {
        block()
    } catch (first: OrsException) {
        if (!first.retryable || !configStore.activateReserve()) throw first
        Log.w(TAG, "Request failed; retrying once on the reserve endpoint", first)
        block()
    }

    private fun execute(request: Request): String {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (io: IOException) {
            // Could be the user's signal, could be a host that no longer exists. Worth one retry.
            throw OrsException("No connection. Check the Kompakt's Wi-Fi or cellular data.", retryable = true)
        }
        response.use {
            val bodyString = it.body?.string().orEmpty()
            if (it.isSuccessful) return bodyString

            // ORS uses two different shapes for `error`: an object for routing failures
            // ({"error":{"code":2010,"message":"…"}}) but a bare string for auth failures
            // ({"error":"Access to this API has been disallowed"}). Only the object form is
            // modelled; the string form fails to decode and falls back to the messages below.
            val error = runCatching {
                json.decodeFromString(OrsErrorResponse.serializer(), bodyString).error
            }.getOrNull()

            throw when {
                // The key is the app's, not the user's. A reserve config may carry a working one.
                it.code == 401 || it.code == 403 ->
                    OrsException("TripTime's map service key was rejected. Try again later.", retryable = true)

                // A 404 is ambiguous here and the distinction matters: ORS answers "no road
                // connects these" (codes 2009/2010) with 404, and so does a server being asked for
                // a path that no longer exists. The presence of an ORS error body separates them —
                // with one it is a real answer, without one the endpoint itself is suspect.
                it.code == 404 && error?.code != null ->
                    OrsException("No driving route found between these locations.")

                it.code == 404 ->
                    OrsException("No driving route found between these locations.", retryable = true)

                // ORS refuses anything over 6,000 km. A deliberate answer, not a fault.
                error?.code == ORS_DISTANCE_LIMIT_EXCEEDED ->
                    OrsException("That's too far to drive. Routes are limited to about 6,000 km (3,700 mi).")

                // Rate limiting is about volume, not about where the request went.
                it.code == 429 ->
                    OrsException("Too many requests to OpenRouteService — wait a moment and try again.")

                else -> OrsException(
                    error?.message ?: "OpenRouteService request failed (HTTP ${it.code}).",
                    retryable = true,
                )
            }
        }
    }
}
