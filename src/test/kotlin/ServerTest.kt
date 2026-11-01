package com.example

import com.example.api.configureRouting
import com.example.persistence.DatabaseRuntime
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ServerTest {

    @Test
    fun `test root endpoint`() = testApplication {
        // Exercise the route without requiring host database credentials.
        application {
            configureRouting(object : DatabaseRuntime {
                override suspend fun isReady(): Boolean = true
                override fun close() = Unit
            })
        }
        // verify server root returns 200
        assertEquals(HttpStatusCode.OK, client.get("/").status)
    }

}
