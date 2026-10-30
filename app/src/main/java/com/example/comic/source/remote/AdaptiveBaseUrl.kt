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
 * prefers the LAN address; on cellular it goes straight to the public one.
 *
 * The probe only ranks candidates. Callers report connection failures back
 * through [resolve]; a base that just failed at connect time is avoided for
 * a while, and a base that served a successful request is pinned until it
 * fails. A slow NAS boot or a router without NAT loopback therefore degrades
 * to the working address after one failed request instead of looping on the
 * wrong one.
 */
class AdaptiveBaseUrl(
    context: Context?,
    private val client: OkHttpClient,
    private val local: HttpUrl,
    private val public: HttpUrl?,
    private val authorize: (Request.Builder) -> Unit = {},
    private val probeOverride: ((HttpUrl) -> Boolean)? = null,
) {
    private val connectivity =
        context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val probeClient = client.newBuilder()
        .connectTimeout(PROBE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(PROBE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val mutex = Mutex()

    @Volatile private var pinned: HttpUrl? = null
    @Volatile private var pinnedNetwork: Network? = null
    @Volatile private var failedBase: HttpUrl? = null
    @Volatile private var failedAt = 0L
    @Volatile private var probedNetwork: Network? = null
    @Volatile private var probedReachable = false
    @Volatile private var probedAt = 0L

    /**
     * Returns the address to try next. [lastFailure] carries the previous
     * attempt's [ConnectivityFailure] so the selector can stop recommending
     * an address that cannot be connected to.
     */
    suspend fun resolve(lastFailure: Throwable? = null): HttpUrl = mutex.withLock {
        val now = System.currentTimeMillis()
        if (lastFailure is ConnectivityFailure) {
            failedBase = lastFailure.base
            failedAt = now
            if (pinned == lastFailure.base) pinned = null
        }

        val network = activeNetwork()
        pinned?.takeIf { pinnedNetwork == network }?.let { return it }
        pinned = null

        val cellular = isCellular(network)
        val bases = ordered(cellular)
        val failed = failedBase?.takeIf { now - failedAt < FAILURE_BLACKOUT_MS }
        var candidate = bases.firstOrNull { it != failed } ?: bases.first()

        // Probe may wrongly report the LAN as down; never let it demote the
        // LAN while the public address is the one that is actually failing.
        if (!cellular && public != null && candidate == local && public != failed) {
            if (!localProbe(network, now)) candidate = public
        }
        candidate
    }

    /** Pins a base that just served a successful request. */
    fun succeed(base: HttpUrl) {
        synchronized(this) {
            if (failedBase == base) return
            pinned = base
            pinnedNetwork = activeNetwork()
        }
    }

    private fun ordered(cellular: Boolean): List<HttpUrl> =
        if (cellular) listOfNotNull(public ?: local) else listOf(local) + listOfNotNull(public)

    private suspend fun localProbe(network: Network?, now: Long): Boolean {
        if (probedNetwork == network && now - probedAt < PROBE_TTL_MS) {
            return probedReachable
        }
        val reachable = probe(local)
        probedNetwork = network
        probedReachable = reachable
        probedAt = now
        return reachable
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

    private suspend fun probe(url: HttpUrl): Boolean {
        probeOverride?.let { return it(url) }
        return withContext(Dispatchers.IO) {
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
    }

    /** Transport-level failure on a specific base; the selector's signal. */
    class ConnectivityFailure(
        val base: HttpUrl,
        cause: Throwable,
    ) : IOException("无法连接 ${base.host}：${cause.message?.take(80)}", cause)

    private companion object {
        const val PROBE_CONNECT_TIMEOUT_MS = 2_500L
        const val PROBE_CALL_TIMEOUT_MS = 3_000L
        const val PROBE_TTL_MS = 60_000L
        const val FAILURE_BLACKOUT_MS = 5 * 60_000L
    }
}
