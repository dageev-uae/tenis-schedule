package org.example.court

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class LoginRequest(
    val user_name: String,
    val password: String,
    val app_id: Int = 3,
    val device_source: String = "web",
    val app_os_version: String = "web",
    val app_version: String = "",
    val ip_address: String = "",
    val user_agent: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
    val access_code: String = "",
    val link_with_uae_pass: Boolean = false
)

@Serializable
data class LoginResponse(
    val access_token: String? = null,
    val token: String? = null,
    val success: Boolean = false,
    val message: String? = null
)

class CourtAPI {
    private val logger = LoggerFactory.getLogger(CourtAPI::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }

    private val baseUrl = "https://digital.damacgroup.com/damacliving/api/v1"
    private val apiToken = "a740e9a60b62418ee08d65d53740c3346eef6edf994a0784f0def9ca13822a9b"
    private val customIdentifier = "35caf711af6f59be5062de4742ed119a"

    private val username = System.getenv("COURT_USERNAME") ?: ""
    private val password = System.getenv("COURT_PASSWORD") ?: ""

    private var accessToken: String? = null

    /**
     * Авторизация на сайте бронирования
     */
    suspend fun authenticate(): Boolean {
        return try {
            logger.info("Attempting to authenticate...")

            val loginRequest = LoginRequest(
                user_name = username,
                password = password
            )

            val response: HttpResponse = client.post("$baseUrl/users/login") {
                contentType(ContentType.Application.Json)
                header("accept", "application/json, text/plain, */*")
                header("accept-language", "en")
                header("api-token", apiToken)
                header("origin", "https://www.damacliving.com")
                header("referer", "https://www.damacliving.com/")
                header("x-custom-identifier", customIdentifier)
                setBody(loginRequest)
            }

            if (response.status.isSuccess()) {
                val loginResponse: LoginResponse = response.body()
                accessToken = loginResponse.access_token ?: loginResponse.token

                if (accessToken != null) {
                    logger.info("Authentication successful")
                    true
                } else {
                    logger.error("Authentication failed: No token in response")
                    false
                }
            } else {
                logger.error("Authentication failed: ${response.status} - ${response.bodyAsText()}")
                false
            }
        } catch (e: Exception) {
            logger.error("Authentication error", e)
            false
        }
    }

    /**
     * Бронирование корта
     * @param date Дата в формате YYYY-MM-DD
     * @param time Время в формате HH:MM
     * @param amenityId ID корта/amenity (опционально, можно добавить в параметры)
     * @return результат бронирования
     */
    suspend fun bookCourt(date: String, time: String): BookingResult {
        return try {
            logger.info("Attempting to book court for $date at $time")

            if (accessToken == null) {
                if (!authenticate()) {
                    return BookingResult.Error("Authentication failed")
                }
            }

            // Реальный эндпоинт для бронирования кортов
            val response: HttpResponse = client.post("$baseUrl/amenities/registration") {
                contentType(ContentType.Application.Json)
                header("accept", "application/json, text/plain, */*")
                header("accept-language", "en")
                header("api-token", apiToken)
                header("authorization", "Bearer $accessToken")
                header("origin", "https://www.damacliving.com")
                header("referer", "https://www.damacliving.com/")
                header("x-custom-identifier", customIdentifier)
                setBody(mapOf(
                    "date" to date,
                    "time" to time
                    // TODO: Добавьте другие необходимые параметры:
                    // "amenity_id" to amenityId,
                    // "duration" to duration,
                    // и т.д.
                ))
            }

            when {
                response.status.isSuccess() -> {
                    logger.info("Booking successful for $date at $time")
                    BookingResult.Success("Court booked successfully")
                }
                response.status == HttpStatusCode.Conflict -> {
                    logger.warn("Court already booked for $date at $time")
                    BookingResult.AlreadyBooked("Court already booked")
                }
                else -> {
                    val body = response.bodyAsText()
                    logger.error("Booking failed: ${response.status} - $body")
                    BookingResult.Error("Booking failed: $body")
                }
            }
        } catch (e: Exception) {
            logger.error("Booking error", e)
            BookingResult.Error("Booking error: ${e.message}")
        }
    }

    /**
     * Закрытие HTTP клиента
     */
    fun close() {
        client.close()
    }
}

sealed class BookingResult {
    data class Success(val message: String) : BookingResult()
    data class AlreadyBooked(val message: String) : BookingResult()
    data class Error(val message: String) : BookingResult()
}
