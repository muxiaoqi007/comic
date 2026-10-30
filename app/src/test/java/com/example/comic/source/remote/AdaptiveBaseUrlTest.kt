package com.example.comic.source.remote

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBaseUrlTest {

    private val local = "http://192.168.1.10:5006".toHttpUrl()
    private val public = "https://nas.example.com".toHttpUrl()

    @Test
    fun wifiPrefersLanWhenProbeSucceeds() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { true } }
        assertEquals(local, adaptive.resolve())
    }

    @Test
    fun wifiSwitchesToPublicWhenLanProbeFails() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { false } }
        assertEquals(public, adaptive.resolve())
    }

    @Test
    fun withoutPublicAddressAlwaysUsesLan() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, null) { probeOverride = { false } }
        assertEquals(local, adaptive.resolve())
    }

    @Test
    fun lanFailureBlackoutsLanAndResolvesPublic() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { true } }
        assertEquals(local, adaptive.resolve())
        val failure = AdaptiveBaseUrl.ConnectivityFailure(local, java.io.IOException("refused"))
        assertEquals(public, adaptive.resolve(failure))
        // Public is now pinned and survives probe results for the LAN.
        assertEquals(public, adaptive.resolve())
    }

    @Test
    fun publicFailureFallsBackToLanEvenWhenProbeIsDown() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { false } }
        assertEquals(public, adaptive.resolve())
        val failure = AdaptiveBaseUrl.ConnectivityFailure(public, java.io.IOException("timeout"))
        // The probe is useless, but the LAN must still be retried after the
        // public address failed — it may be reachable in reality.
        assertEquals(local, adaptive.resolve(failure))
    }

    @Test
    fun succeedPinsBaseAcrossNetworkLookups() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { false } }
        adaptive.succeed(local)
        assertEquals(local, adaptive.resolve())
        assertEquals(local, adaptive.resolve())
    }

    @Test
    fun failureOverridesPin() = runBlocking {
        val adaptive = AdaptiveBaseUrl(null, client(), local, public) { probeOverride = { true } }
        adaptive.succeed(local)
        assertEquals(local, adaptive.resolve())
        val failure = AdaptiveBaseUrl.ConnectivityFailure(local, java.io.IOException("refused"))
        assertEquals(public, adaptive.resolve(failure))
    }

    private var probeOverride: ((okhttp3.HttpUrl) -> Boolean)? = null

    private fun client() = okhttp3.OkHttpClient()

    private fun AdaptiveBaseUrl(
        context: android.content.Context?,
        client: okhttp3.OkHttpClient,
        local: okhttp3.HttpUrl,
        public: okhttp3.HttpUrl?,
        authorize: (okhttp3.Request.Builder) -> Unit = {},
        configure: AdaptiveBaseUrlTest.() -> Unit = {},
    ): AdaptiveBaseUrl {
        configure()
        return AdaptiveBaseUrl(
            context = context,
            client = client,
            local = local,
            public = public,
            authorize = authorize,
            probeOverride = probeOverride,
        )
    }
}
