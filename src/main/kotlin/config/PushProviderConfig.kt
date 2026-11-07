package com.example.config

import com.example.external.ApnsEnvironment

class RuStorePushConfig(val projectId: String, val serviceToken: String)

class ApnsPushConfig(
    val teamId: String,
    val keyId: String,
    val privateKeyPem: String,
    val topic: String,
    val environment: ApnsEnvironment,
)
