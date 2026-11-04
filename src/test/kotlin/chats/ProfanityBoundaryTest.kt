package com.example.chats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfanityBoundaryTest {
    @Test
    fun `maximum ordinary word remains allowed`() {
        assertFalse(rejected("a".repeat(4096)))
    }

    @Test
    fun `maximum bounded masked token remains allowed`() {
        assertFalse(rejected("a-".repeat(2047) + "a"))
    }

    @Test
    fun `maximum spaced letters retain exact word distinction`() {
        assertFalse(rejected("a ".repeat(2047) + "a"))
        assertTrue(rejected("a ".repeat(2043) + "б л я д ь"))
    }

    private fun rejected(text: String): Boolean {
        assertTrue(text.codePointCount(0, text.length) <= 4096)
        return ApprovedProfanityDetector.containsProfanity(text)
    }
}
