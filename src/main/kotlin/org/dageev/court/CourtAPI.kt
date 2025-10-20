package org.dageev.court

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

@Serializable
data class LoginRequest(
    val user_name: String,
    val password: String,
    val app_id: Int = 3,
    val device_source: String = "web",
    val app_os_version: String = "web",
    val app_version: String = "",
    val ip_address: String = "104.28.218.188",
    val user_agent: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
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

        install(Logging) {
            logger = object : Logger {
                private val log = org.slf4j.LoggerFactory.getLogger("HttpClient")
                override fun log(message: String) {
                    log.info(message)
                }
            }
            level = LogLevel.HEADERS
        }

        // Automatically decompress gzip responses
        engine {
            endpoint {
                keepAliveTime = 5000
                connectTimeout = 30000
                requestTimeout = 30000
            }
        }
    }

    private val baseUrl = "https://digital.damacgroup.com/damacliving/api/v1"
    private val apiToken = "a740e9a60b62418ee08d65d53740c3346eef6edf994a0784f0def9ca13822a9b"
    private val customIdentifierAuth = "8c619de8da1ac706a66276d3ecdd1054"
    private val customIdentifierBooking = "2f936759a34caa82fb9fc1a809c17c0d"

    private val username = System.getenv("COURT_USERNAME")?.trim() ?: ""
    private val password = System.getenv("COURT_PASSWORD")?.trim() ?: ""

    private var accessToken: String? = null
    private var accountId: String? = null

    /**
     * Декодирует gzip-сжатое тело ответа
     */
    private suspend fun readResponseBody(response: HttpResponse): String {
        val contentEncoding = response.headers["Content-Encoding"]
        val bodyBytes = response.readBytes()

        return if (contentEncoding == "gzip" && bodyBytes.isNotEmpty()) {
            try {
                GZIPInputStream(ByteArrayInputStream(bodyBytes)).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                logger.warn("Failed to decompress gzip body, trying as plain text", e)
                String(bodyBytes, Charsets.UTF_8)
            }
        } else {
            String(bodyBytes, Charsets.UTF_8)
        }
    }

    /**
     * Авторизация на сайте бронирования
     */
    suspend fun authenticate(): Boolean {
        return try {
            logger.info("Attempting to authenticate with username: $username")

            // Проверяем, что username и password не пустые
            if (username.isEmpty() || password.isEmpty()) {
                logger.error("Username or password is empty! username='$username', password length=${password.length}")
                return false
            }

            val loginRequest = LoginRequest(
                user_name = username,
                password = password
            )

            logger.info("Login request body: user_name=$username, password=[HIDDEN], ip_address=${loginRequest.ip_address}")
            logger.info("Using api-token: ${apiToken.take(20)}..., x-custom-identifier: $customIdentifierAuth")

            val response: HttpResponse = client.post("$baseUrl/users/login") {
                contentType(ContentType.Application.Json)
                header("accept", "application/json, text/plain, */*")
                header("accept-language", "en")
                header("api-token", apiToken)
                header("dnt", "1")
                header("origin", "https://www.damacliving.com")
                header("priority", "u=1, i")
                header("referer", "https://www.damacliving.com/")
                header("sec-ch-ua", "\"Chromium\";v=\"141\", \"Not?A_Brand\";v=\"8\"")
                header("sec-ch-ua-mobile", "?0")
                header("sec-ch-ua-platform", "\"macOS\"")
                header("sec-fetch-dest", "empty")
                header("sec-fetch-mode", "cors")
                header("sec-fetch-site", "cross-site")
                header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                header("x-custom-identifier", customIdentifierAuth)
                setBody(loginRequest)
            }

            logger.info("Response status: ${response.status.value}, headers: ${response.headers.entries().joinToString { "${it.key}=${it.value}" }}")

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
                try {
                    val errorBody = readResponseBody(response)
                    logger.error("Authentication failed with status: ${response.status.value} ${response.status.description}, body: $errorBody")
                } catch (e: Exception) {
                    logger.error("Authentication failed with status: ${response.status.value} ${response.status.description}, could not read body: ${e.message}")
                }
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
                header("dnt", "1")
                header("origin", "https://www.damacliving.com")
                header("priority", "u=1, i")
                header("referer", "https://www.damacliving.com/")
                header("sec-ch-ua", "\"Chromium\";v=\"141\", \"Not?A_Brand\";v=\"8\"")
                header("sec-ch-ua-mobile", "?0")
                header("sec-ch-ua-platform", "\"macOS\"")
                header("sec-fetch-dest", "empty")
                header("sec-fetch-mode", "cors")
                header("sec-fetch-site", "cross-site")
                header("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36")
                header("x-custom-identifier", customIdentifierBooking)
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
                    // Не читаем тело ответа при ошибке, так как может быть проблема с кодировкой
                    logger.error("Booking failed with status: ${response.status.value} ${response.status.description}")
                    BookingResult.Error("Booking failed with status: ${response.status.value}")
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
