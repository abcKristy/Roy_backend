package com.example.chats

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageContentPolicyTest {
    private val policy = MessageContentPolicy()

    @Test
    fun `all approved profanity forms are blocked as whole words`() {
        val forms = """
            хуй хуя хую хуем хуи хуев хуями нахуй похуй охуеть охуел охуела охуенный охуенно хуевый хуйня хуйню хуйне хуйней
            пизда пизды пизде пизду пиздой пиздец пиздеть пизди пиздишь пиздил пиздила пиздюк распиздяй пиздатый
            ебать ебал ебала ебали ебаный ебучий ебись заебал заебала заебали заебись наебал наебала выебать съебись уебок ебло ебнутый
            блядь блять бля блядский блядская блядское блядина
            fuck fucks fucking fucked fucker fuckers motherfucker motherfuckers motherfucking
            shit shits shitty bullshit bitch bitches cunt cunts asshole assholes arsehole arseholes
        """.trimIndent().split(Regex("\\s+"))
        forms.forEach { assertTrue(policy.rejected("Слово: $it!"), it) }
        assertTrue(policy.rejected("СЛОВО: БЛЯТЬ!"))
        assertTrue(policy.rejected("ёбаный"))
        assertTrue(policy.rejected("xуй"))
        assertTrue(policy.rejected("ё\u200Bбать"))
        assertTrue(policy.rejected("х*й"))
        assertTrue(policy.rejected("п-и-з-д-е-ц"))
        assertTrue(policy.rejected("б л я д ь"))
        assertTrue(policy.rejected("я б л я д ь"))
        assertTrue(policy.rejected("я х у й"))
        assertTrue(policy.rejected("f.u.c.k"))
        assertTrue(policy.rejected("this is bullshit"))
        assertTrue(policy.rejected("you bitch"))
    }

    @Test
    fun `ordinary words and nonapproved variants stay allowed`() {
        listOf(
            "страхуй", "подстрахуй", "страхую", "хулиган", "математика", "матч", "материал",
            "блин", "благодарю", "class", "assessment", "Dick", "Scunthorpe", "cocktail",
            "passion", "hello", "damn", "hell", "crap", "suck", "ass", "dick", "pussy",
            "Он хулиган", "Нужна подстраховка", "Ты неприятный человек", "Никому здесь не рады",
            "х.й", "х-й", "бляяядь", "х у л и г а н", "я иду", "я у дома", "у моста",
            "я б л и н", "б\nл я д ь", "б  л я д ь",
        ).forEach { assertFalse(policy.rejected(it), it) }
    }

    @Test
    fun `only approved phone shapes and contact context are blocked`() {
        listOf(
            "Пиши +7 (999) 123-45-67", "my phone +1 202 555 0143", "звони 123 45 67",
            "тел: 12345678", "phone 123-45-67", "79991234567", "89991234567", "9991234567",
        ).forEach { assertTrue(policy.rejected(it), it) }
        listOf(
            "Встречаемся в 12:30 у дома 12", "номер заказа 123 45 67", "01.11.2026",
            "2026-11-01", "дом 12, квартира 45", "1000 рублей", "55.7558, 37.6173",
            "12345678", "звони 123 45 6", "+1 202  555    0143",
        ).forEach { assertFalse(policy.rejected(it), it) }
    }

    @Test
    fun `approved domain and IP forms are blocked without broad substring matching`() {
        listOf(
            "смотри https://example.com", "example.com", "t . me / name", "name@example.com",
            "http://[2001:db8::1]/", "192.168.1.1", "пример.рф", "h t t p s : / / example.com",
            "example . com", "vk.com/id", "ftp://example.org", "www.example.net",
        ).forEach { assertTrue(policy.rejected(it), it) }
        listOf(
            "версия 1.2.3", "2001:db8", "почта на столе", "ул. Ленина", "т. е.",
            "10.5 км", "пример", "http://", "www.", "ftp://", "h t t p s : / /",
            "example . unknownsuffix", "ex ample.com", "@nickname", "точка ком", "example.unknownsuffix",
            "256.256.256.256",
        ).forEach { assertFalse(policy.rejected(it), it) }
    }
}
