package com.example.chats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkBoundaryTest {
    private val policy = MessageContentPolicy(ProfanityDetector { false })

    @Test
    fun `maximum length ordinary label is allowed`() {
        assertFalse(rejected("a".repeat(MAX_CODE_POINTS)))
    }

    @Test
    fun `many domain labels retain the approved bare suffix distinction`() {
        assertFalse(rejected(manyLabels("unknownsuffix")))
        assertTrue(rejected(manyLabels("com")))
        assertTrue(rejected("https://" + manyLabels("unknownsuffix", MAX_CODE_POINTS - "https://".length)))
        assertTrue(rejected("www." + manyLabels("unknownsuffix", MAX_CODE_POINTS - "www.".length)))
    }

    @Test
    fun `bounded explicit host retains label and split label distinction`() {
        val prefix = "https://"
        val suffix = ".unknownsuffix"
        val labelLength = MAX_CODE_POINTS - prefix.length - suffix.length
        assertTrue(rejected(prefix + "a".repeat(labelLength) + suffix))
        assertFalse(rejected(prefix + "a".repeat(labelLength / 2) + " " +
            "a".repeat(labelLength - labelLength / 2 - 1) + suffix))
    }

    private fun manyLabels(suffix: String, budget: Int = MAX_CODE_POINTS): String =
        "a.".repeat((budget - suffix.length) / 2) + suffix

    private fun rejected(text: String): Boolean {
        assertTrue(text.codePointCount(0, text.length) <= MAX_CODE_POINTS)
        return policy.rejected(text)
    }

    private companion object {
        const val MAX_CODE_POINTS = 4096
    }
}
