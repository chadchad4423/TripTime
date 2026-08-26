package com.chad.triptime.data

import android.util.Base64
import android.util.Log
import com.chad.triptime.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "RemoteConfig"

/** AES-GCM standard: 12-byte nonce, 128-bit tag. */
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128

/**
 * Where TripTime sends its requests, and anything it needs to tell the user. Every field has a
 * working compiled-in default, so this whole mechanism can fail — no network, bad JSON, GitHub
 * down, a key that will not decrypt — and the app carries on exactly as it would have without it.
 * That is the central design rule: remote config may only ever *improve* the situation.
 */
data class AppConfig(
    val apiBase: String,
    val autocompletePath: String,
    val searchPath: String,
    val directionsPath: String,
    val apiKey: String,
    val latestVersion: String?,
    val message: String?,
) {
    companion object {
        /** What the app ships with, and falls back to whenever remote config is unavailable. */
        val COMPILED_IN = AppConfig(
            apiBase = "https://api.heigit.org",
            autocompletePath = "/pelias/v1/autocomplete",
            searchPath = "/pelias/v1/search",
            directionsPath = "/openrouteservice/v2/directions/driving-car",
            apiKey = BuildConfig.ORS_API_KEY,
            latestVersion = null,
            message = null,
        )
    }
}

/** The wire shape of config.json. Every field optional: an older app must tolerate a newer file. */
@Serializable
private data class RemoteConfigDto(
    @SerialName("apiBase") val apiBase: String? = null,
    @SerialName("autocompletePath") val autocompletePath: String? = null,
    @SerialName("searchPath") val searchPath: String? = null,
    @SerialName("directionsPath") val directionsPath: String? = null,
    /** AES-256-GCM, base64 of (12-byte IV || ciphertext || tag). Normally absent entirely. */
    @SerialName("apiKeyEncrypted") val apiKeyEncrypted: String? = null,
    @SerialName("latestVersion") val latestVersion: String? = null,
    @SerialName("message") val message: String? = null,
)

/**
 * Holds the configuration the rest of the app reads, starting from [AppConfig.COMPILED_IN] and
 * replaced once — if ever — by a successful fetch.
 *
 * Deliberately **not persisted**. The fetched value lives for the session and is looked up again
 * next launch, which keeps `ui/PrivacyScreen.kt`'s "the only thing stored on your phone is your
 * unit preference" true (DECISIONS.md D-020, and the D-008 rule that page must stay accurate).
 */
class RemoteConfigStore {
    /** What requests actually use. Starts compiled-in and stays there until something fails. */
    @Volatile
    var current: AppConfig = AppConfig.COMPILED_IN
        private set

    /**
     * Endpoint and key overrides from remote config, deliberately **not** applied on arrival.
     *
     * This is the whole point of the design: a config file that is wrong, stale or malicious
     * cannot break an app that would otherwise work, because these values are only ever reached
     * after a request has already failed. Held in reserve, promoted by [activateReserve], and
     * then only once per session.
     */
    @Volatile
    private var reserve: AppConfig? = null

    /**
     * Advisory text, applied the moment it arrives. Safe to trust immediately because it cannot
     * affect a request — the worst a bad value can do is show the wrong sentence.
     */
    @Volatile
    var notice: String? = null
        private set

    fun offer(config: AppConfig, notice: String?) {
        this.notice = notice
        // Nothing to fall back to if it points where we already are.
        if (config.endpointsDifferFrom(current)) reserve = config
    }

    /**
     * Swap to the reserve after a failure. Returns true only if there was something new to swap
     * to, which is the caller's signal that retrying is worth it rather than pointless.
     */
    fun activateReserve(): Boolean {
        val candidate = reserve ?: return false
        reserve = null
        current = candidate
        return true
    }
}

private fun AppConfig.endpointsDifferFrom(other: AppConfig): Boolean =
    apiBase != other.apiBase ||
        autocompletePath != other.autocompletePath ||
        searchPath != other.searchPath ||
        directionsPath != other.directionsPath ||
        apiKey != other.apiKey

/**
 * Fetches [AppConfig] from the first config URL that answers.
 *
 * Two URLs, on deliberately different GitHub surfaces — raw.githubusercontent.com and GitHub
 * Pages — so that a change to one host's URL structure cannot silently take out both. That is not
 * hypothetical: D-018 was exactly this failure applied to the API itself, and a safety net that
 * breaks quietly is worse than none, because nobody looks at it until the day it is needed.
 *
 * Every failure here is survivable by construction: no network, a 404, malformed JSON, a key that
 * will not decrypt — all of it ends with the caller keeping [AppConfig.COMPILED_IN]. There is no
 * error path that leaves the app worse off than not having remote config at all, which is what
 * lets this run on a background thread at launch and be ignored if it never finishes.
 */
class RemoteConfigFetcher(
    private val urls: List<String> = listOf(
        BuildConfig.CONFIG_URL,
        "https://chadchad4423.github.io/TripTime/config.json",
    ),
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): AppConfig? = withContext(Dispatchers.IO) {
        for (url in urls) {
            val dto = runCatching { fetchOne(url) }
                .onFailure { Log.w(TAG, "Config fetch failed for $url", it) }
                .getOrNull()
            if (dto != null) return@withContext dto.toAppConfig()
        }
        null
    }

    private fun fetchOne(url: String): RemoteConfigDto? {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return json.decodeFromString(RemoteConfigDto.serializer(), body)
        }
    }

    private fun RemoteConfigDto.toAppConfig(): AppConfig {
        val fallback = AppConfig.COMPILED_IN
        // A remote key that will not decrypt is treated as absent, never as a reason to fail.
        val remoteKey = apiKeyEncrypted
            ?.let { runCatching { decryptApiKey(it) }.onFailure { e -> Log.w(TAG, "key decrypt failed", e) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return AppConfig(
            apiBase = apiBase?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: fallback.apiBase,
            autocompletePath = autocompletePath?.takeIf { it.isNotBlank() } ?: fallback.autocompletePath,
            searchPath = searchPath?.takeIf { it.isNotBlank() } ?: fallback.searchPath,
            directionsPath = directionsPath?.takeIf { it.isNotBlank() } ?: fallback.directionsPath,
            apiKey = remoteKey ?: fallback.apiKey,
            latestVersion = latestVersion?.takeIf { it.isNotBlank() },
            message = message?.takeIf { it.isNotBlank() },
        )
    }
}

/**
 * AES-256-GCM, with the key compiled in from `local.properties`.
 *
 * The point of encrypting is narrow and worth stating plainly: it stops the key being harvested by
 * scrapers and secret-scanners that pattern-match public files. It does **not** stop a person who
 * decompiles the APK, because the decryption key is in there — which is the same protection the
 * embedded ORS key has always had. Parity, not an upgrade: the gain is the ability to rotate.
 */
private fun decryptApiKey(encoded: String): String? {
    val secret = BuildConfig.CONFIG_DECRYPT_KEY
    if (secret.isBlank()) return null

    val blob = Base64.decode(encoded, Base64.DEFAULT)
    if (blob.size <= GCM_IV_BYTES) return null

    val key = SecretKeySpec(Base64.decode(secret, Base64.DEFAULT), "AES")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
    }
    val plain = cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    return String(plain, Charsets.UTF_8)
}
