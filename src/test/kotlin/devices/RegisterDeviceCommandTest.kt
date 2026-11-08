package com.example.devices

import com.example.auth.SessionIdentity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse

class RegisterDeviceCommandTest {
    @Test
    fun `diagnostic text does not expose push token`() {
        val identity = SessionIdentity(UUID.randomUUID(), UUID.randomUUID(), 0)
        val token = "sensitive-push-token-fixture"
        val command = RegisterDeviceCommand(identity, UUID.randomUUID(), "ANDROID_RUSTORE", token)

        assertFalse(command.toString().contains(token))
    }
}
