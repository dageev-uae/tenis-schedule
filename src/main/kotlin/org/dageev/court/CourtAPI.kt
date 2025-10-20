package org.dageev.court

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
    val data: LoginData? = null,
    val meta_data: MetaData? = null
)

@Serializable
data class LoginData(
    val party: Party? = null
)

@Serializable
data class Party(
    val access_token: String? = null,
    val customer_name: String? = null,
    val customer_type: String? = null,
    val account_id: String? = null
)

@Serializable
data class BookingRequest(
    val origin: String = "Portal",
    val fm_case_id: String = "",
    val account_id: String,
    val booking_unit_id: String = "a0x07000008cCMPAA2",
    val amenity_id: String = "a5Y1n000000eVcpEAE",
    val amenity_slot_id: String = "a5XTY0000000EpN2AU",
    val booking_date: String,
    val no_of_guest: Int = 2,
    val comments: String = ""
)

@Serializable
data class MetaData(
    val title: String? = null,
    val message: String? = null,
    val status_code: Int? = null
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
    private var accountId: String? = null

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
                accessToken = loginResponse.data?.party?.access_token
                accountId = loginResponse.data?.party?.account_id

                if (accessToken != null && accountId != null) {
                    logger.info("Authentication successful for user: ${loginResponse.data?.party?.customer_name}, account_id: $accountId")
                    true
                } else {
                    logger.error("Authentication failed: No token or account_id in response")
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
     * @return результат бронирования
     */
    suspend fun bookCourt(date: String): BookingResult {
        return try {
            logger.info("Attempting to book court for $date")

            if (accessToken == null || accountId == null) {
                if (!authenticate()) {
                    return BookingResult.Error("Authentication failed")
                }
            }

            val bookingRequest = BookingRequest(
                account_id = accountId!!,
                booking_date = date
            )

            val response: HttpResponse = client.post("$baseUrl/amenities/registration") {
                contentType(ContentType.Application.Json)
                header("accept", "application/json, text/plain, */*")
                header("accept-language", "en")
                header("api-token", apiToken)
                header("authorization", "Bearer $accessToken")
                header("origin", "https://www.damacliving.com")
                header("referer", "https://www.damacliving.com/")
                header("x-custom-identifier", customIdentifier)
                setBody(bookingRequest)
            }

            when {
                response.status.isSuccess() -> {
                    logger.info("Booking successful for $date")
                    BookingResult.Success("Court booked successfully")
                }
                response.status == HttpStatusCode.Conflict -> {
                    logger.warn("Court already booked for $date")
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
