package com.wolfy.data

import com.wolfy.data.companion.CompanionPhrase
import com.wolfy.data.companion.CompanionPhrasePack
import com.wolfy.data.companion.CompanionProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class SyncCompanionTest {
    @Test
    fun wireContractWrapsProfileAndPackSeparately() {
        val pack = CompanionPhrasePack(
            profileHash = "abc123",
            source = CompanionPhrasePack.SOURCE_GENERATED,
            phrases = listOf(CompanionPhrase("session_start.01", "session_start", "Привет")),
        )
        val profile = CompanionProfile(
            id = "123e4567-e89b-42d3-a456-426614174000",
            name = "Лис",
            phrasePack = pack,
            profileHash = "abc123",
            rev = 17,
        )

        val wire = profile.toSyncCompanion()
        assertNull(wire.profile.phrasePack)
        assertEquals(pack, wire.phrasePack)
        assertEquals(17, wire.rev)

        val encoded = Json.encodeToString(SyncCompanion.serializer(), wire)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertTrue("profile" in root)
        assertTrue("phrasePack" in root)
        assertFalse("name" in root, "поля профиля не должны лежать в корне sync-конверта")

        assertEquals(profile, Json.decodeFromString(SyncCompanion.serializer(), encoded).toCompanionProfile())
    }
}
