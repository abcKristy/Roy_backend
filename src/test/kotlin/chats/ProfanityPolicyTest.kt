package com.example.chats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object ProfanityPolicyFixture {
    private val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/chats/profanity-v1.0.json"))
        .bufferedReader().use { it.readText() }).jsonObject
    val version = fixture.getValue("version").jsonPrimitive.content
    private fun strings(key: String) = fixture.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    val forms = strings("forms")
    val blocked = forms + strings("normalizedBlocked")
    val allowed = strings("allowed")
}

class ProfanityPolicyTest {
    @Test
    fun `approved exact forms and normalization reject deterministically`() {
        assertEquals("chat-content-v1.0", ProfanityPolicyFixture.version)
        assertEquals(MessageContentPolicy.VERSION, ProfanityPolicyFixture.version)
        repeat(2) {
            ProfanityPolicyFixture.blocked.forEachIndexed { index, text ->
                assertTrue(ApprovedProfanityDetector.containsProfanity(text), "Blocked fixture index $index")
            }
        }
    }

    @Test
    fun `approved exceptions and unconfirmed patterns remain allowed`() {
        ProfanityPolicyFixture.allowed.forEachIndexed { index, text ->
            assertFalse(ApprovedProfanityDetector.containsProfanity(text), "Allowed fixture index $index")
        }
        // A long masked whole token must not be truncated to its dictionary prefix.
        assertFalse(ApprovedProfanityDetector.containsProfanity("f-u-c-k-a-a-a-a-a-a-a"))
    }

    @Test
    fun `each exact single word requires whole word boundaries`() {
        ProfanityPolicyFixture.forms.filterNot { it.contains(' ') }.forEachIndexed { index, text ->
            assertFalse(ApprovedProfanityDetector.containsProfanity("z${text}z"), "Letter boundary fixture index $index")
            assertFalse(ApprovedProfanityDetector.containsProfanity("1${text}1"), "Digit boundary fixture index $index")
        }
    }
}
