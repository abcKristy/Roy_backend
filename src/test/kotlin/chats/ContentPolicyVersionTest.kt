package com.example.chats

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Pinned approved v1.0 corpus: source section 8 plus normalization/exception boundaries. */
class ContentPolicyVersionTest {
    private val fixture: JsonObject = javaClass.getResourceAsStream("/chats/chat-content-v1.0.json").use {
        Json.parseToJsonElement(assertNotNull(it).bufferedReader(Charsets.UTF_8).readText().removePrefix("\uFEFF")).jsonObject
    }

    @Test
    fun `implementation normalization and exceptions share the approved fixture version`() {
        assertEquals("chat-content-v1.0", MessageContentPolicy.VERSION)
        for (field in listOf("version", "normalizationVersion", "exceptionSetVersion")) {
            assertEquals(MessageContentPolicy.VERSION, fixture.getValue(field).jsonPrimitive.content)
        }
        assertEquals("spec/for dev/artifacts/chat-message-content-policy.md", fixture.getValue("source").jsonPrimitive.content)
        assertEquals("1.0, 01.11.2026", fixture.getValue("sourceRevision").jsonPrimitive.content)
        assertEquals("02.11.2026", fixture.getValue("authorApprovalDate").jsonPrimitive.content)
        assertTrue(fixture.getValue("sourceSha256").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `approved minimum corpus normalization and deliberate exceptions remain deterministic`() {
        val policy = MessageContentPolicy()
        for ((group, expected) in listOf("blocked" to true, "allowed" to false, "normalizedBlocked" to true, "boundedAllowed" to false)) {
            for (value in fixture.getValue(group).jsonArray) {
                val text = value.jsonPrimitive.content
                repeat(2) { assertEquals(expected, policy.rejected(text), "Approved fixture group $group") }
            }
        }
    }

    @Test
    fun `phone and URL gates remain independent of the injected profanity detector`() {
        val withoutProfanity = MessageContentPolicy(ProfanityDetector { false })
        assertFalse(withoutProfanity.rejected("блять"))
        assertTrue(withoutProfanity.rejected("Пиши +7 (999) 123-45-67"))
        assertTrue(withoutProfanity.rejected("example.com"))
        val custom = MessageContentPolicy(ProfanityDetector { it == "synthetic detector marker" })
        assertTrue(custom.rejected("synthetic detector marker"))
        assertFalse(custom.rejected("ordinary allowed fixture"))
    }
}
