package com.example.comic.source.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Picks the LAN or public address for a NAS source. On Wi-Fi/Ethernet it
 * probes the LAN address first; on cellular it goes straight to the public
 * address. The choice is cached briefly and re-evaluated when the active
 * network changes, so moving between home and mobile data switches naturally.
 */
class AdaptiveBaseUrl(
    context: Context?,
    private val client: OkHttpClient,
    private val local: HttpUrl,
    private val public: HttpUrl?,
    private val authorize: (Request.Builder) -> Unit = {},
) {
    private val connectivity =
        context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val probeClient = client.newBuilder()
        .connectTimeout(1_200, TimeUnit.MILLISECONDS)
        .readTimeout(1_200, TimeUnit.MILLISECONDS)
        .callTimeout(1_600, TimeUnit.MILLISECONDS)
        .build()
    private val mutex = Mutex()
    private var chosen: HttpUrl? = null
    private var chosenNetwork: Network? = null
    private var chosenAt = 0L

    suspend fun resolve(): HttpUrl = mutex.withLock {
        val network = activeNetwork()
        val now = System.currentTimeMillis()
        val cached = chosen
        if (
            cached != null &&
            chosenNetwork == network &&
            now - chosenAt < CACHE_TTL_MS
        ) {
            return cached
        }
        val cellular = isCellular(network)
        val reachable = if (public != null && !cellular) probe(local) else false
        val result = chooseBase(local, public, cellular, reachable)
        chosen = result
        chosenNetwork = network
        chosenAt = now
        result
    }

    private fun activeNetwork(): Network? =
        runCatching { connectivity?.activeNetwork }.getOrNull()

    private fun isCellular(network: Network?): Boolean {
        val caps = runCatching { connectivity?.getNetworkCapabilities(network) }.getOrNull()
            ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private suspend fun probe(url: HttpUrl): Boolean = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "Comic/1.0")
        authorize(builder)
        val request = builder.build()
        try {
            probeClient.newCall(request).execute().use { true }
        } catch (e: IOException) {
            false
        }
    }

    private companion object {
        const val CACHE_TTL_MS = 60_000L
    }
}

internal fun chooseBase(
    local: HttpUrl,
    public: HttpUrl?,
    cellular: Boolean,
    localReachable: Boolean,
): HttpUrl = when {
    public == null -> local
    cellular -> public
    localReachable -> local
    else -> public
}
