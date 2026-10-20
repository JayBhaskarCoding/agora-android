package com.example.agora.utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DisifyResponse(
    val format: Boolean,
    val domain: String? = null,
    val disposable: Boolean,
    val dns: Boolean
)

object EmailValidator {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun isEmailReal(email: String): Boolean {
        return try {
            // Disify is completely free and requires no API key
            val response: DisifyResponse = client.get("https://www.disify.com/api/email/$email").body()

            // 1. format must be true
            // 2. disposable must be false (blocks 10minutemail, etc.)
            // 3. dns must be true (proves the domain actually has active mail servers)
            response.format && !response.disposable && response.dns

        } catch (e: Exception) {
            // If the user's internet drops during the check, default to true
            // so we don't accidentally block a real user from signing up.
            true
        }
    }
}