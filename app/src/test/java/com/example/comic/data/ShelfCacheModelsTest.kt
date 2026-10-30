package com.example.comic.data

import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfCacheModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun missingEnabledFieldDefaultsToTrue() {
        val config = json.decodeFromString<SourceConfig>(
            """{"id":"nas","type":"WEBDAV","name":"NAS"}""",
        )

        assertTrue(config.enabled)
    }

    @Test
    fun nasPublicAddressRoundTrips() {
        val config = SourceConfig(
            id = "nas",
            type = SourceType.WEBDAV,
            name = "NAS",
            baseUrl = "http://192.168.1.10:5006",
            publicBaseUrl = "https://nas.example.com",
            rootPath = "/comics",
            username = "reader",
            password = "secret",
        )

        val restored = json.decodeFromString<SourceConfig>(json.encodeToString(config))

        assertEquals(config, restored)
    }

    @Test
    fun cachedShelfRoundTrips() {
        val snapshot = CachedShelf(
            sourceId = "nas",
            sourceName = "NAS",
            sourceType = SourceType.WEBDAV,
            series = listOf(
                Series(
                    id = "series-1",
                    sourceId = "nas",
                    libraryId = "library",
                    title = "海街日记",
                    bookCount = 9,
                ),
            ),
            savedAt = 42L,
        )

        val restored = json.decodeFromString<CachedShelf>(json.encodeToString(snapshot))

        assertEquals(snapshot, restored)
    }
}
