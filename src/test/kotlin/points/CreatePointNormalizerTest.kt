package com.example.points

import com.example.persistence.DatabaseRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CreatePointNormalizerTest {
    private val valid = CreatePointCommand(
        UUID.randomUUID(), UUID.randomUUID(), PointPosition(55.75, 37.62),
        PointTopicInput("other", "  Шахматы  "), "  Москва, улица  ",
        PointAddressSource.MANUAL, false,
    )

    @Test
    fun `normalization trims text without changing coordinates or source`() {
        val normalized = valid.normalized()
        assertEquals("Москва, улица", normalized.address)
        assertEquals("Шахматы", normalized.topic.customLabel)
        assertEquals(valid.position, normalized.position)
        assertEquals(PointAddressSource.MANUAL, normalized.addressSource)
        assertEquals(PointAddressSource.GEOCODED, valid.copy(addressSource = PointAddressSource.GEOCODED).normalized().addressSource)
    }

    @Test
    fun `invalid fields fail before transaction or child writes`() = runBlocking {
        var transactions = 0
        val database = object : DatabaseRuntime {
            override suspend fun isReady() = true
            override suspend fun <T> withJdbcTransaction(block: (Connection) -> T): T {
                transactions++
                error("Transaction must not start")
            }
            override fun close() = Unit
        }
        val service = PointWriteService(database)
        val invalid = listOf(
            valid.copy(position = PointPosition(Double.NaN, 37.62)),
            valid.copy(position = PointPosition(91.0, 37.62)),
            valid.copy(position = PointPosition(55.75, Double.POSITIVE_INFINITY)),
            valid.copy(position = PointPosition(55.75, -181.0)),
            valid.copy(address = "   "),
            valid.copy(address = "a".repeat(301)),
            valid.copy(topic = PointTopicInput("unknown")),
            valid.copy(topic = PointTopicInput("other", " ")),
            valid.copy(topic = PointTopicInput("other", "a".repeat(41))),
            valid.copy(topic = PointTopicInput("football", "label")),
        )
        for (command in invalid) {
            assertFailsWith<IllegalArgumentException> {
                service.create(command, { _, _ -> buildJsonObject {} }, { buildJsonObject {} })
            }
        }
        assertEquals(0, transactions)
    }
}
