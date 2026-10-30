package com.example.comic.source.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveBaseUrlTest {

    private val local = "http://192.168.1.10:5006".toHttpUrl()
    private val public = "https://nas.example.com".toHttpUrl()

    @Test
    fun withoutPublicAddressAlwaysUsesLan() {
        assertEquals(local, chooseBase(local, null, cellular = true, localReachable = false))
    }

    @Test
    fun cellularUsesPublicAddress() {
        assertEquals(public, chooseBase(local, public, cellular = true, localReachable = true))
    }

    @Test
    fun wifiUsesLanWhenItAnswers() {
        assertEquals(local, chooseBase(local, public, cellular = false, localReachable = true))
    }

    @Test
    fun wifiFallsBackToPublicWhenLanIsUnreachable() {
        assertEquals(public, chooseBase(local, public, cellular = false, localReachable = false))
    }
}
