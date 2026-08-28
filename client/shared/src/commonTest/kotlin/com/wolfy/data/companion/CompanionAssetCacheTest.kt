package com.wolfy.data.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class CompanionAssetCacheTest {
    @Test
    fun assetFilesAreLoadedRelativeToCompanionResourceDirectory() = runBlocking {
        val requested = mutableListOf<String>()
        val resources = mapOf(
            "files/companions/manifest.json" to """
                {
                  "schemaVersion": 1,
                  "packId": "test-pack",
                  "packVersion": 1,
                  "canvas": {"width": 1024, "height": 1024},
                  "layerOrder": ["base"],
                  "assets": [{
                    "id": "base.base",
                    "slot": "base",
                    "file": "layers/base/base.svg",
                    "anchorsVersion": 1
                  }]
                }
            """.trimIndent().encodeToByteArray(),
            "files/companions/layers/base/base.svg" to """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
                  <path d="M0 0 L10 0 L10 10 Z" fill="var(--wolfy-skin)"/>
                </svg>
            """.trimIndent().encodeToByteArray(),
        )
        val cache = CompanionAssetCache { path ->
            requested += path
            resources[path] ?: error("unexpected resource path: $path")
        }

        assertNotNull(cache.ensureLoaded())
        assertNotNull(cache.get("base.base"))

        assertEquals(
            listOf("files/companions/manifest.json", "files/companions/layers/base/base.svg"),
            requested,
        )
    }
}
