package org.dageev.scheduler

import kotlinx.coroutines.*
import org.dageev.bot.TelegramBot
import org.dageev.court.BookingResult
import org.dageev.court.CourtAPI
import org.dageev.database.models.Booking
import org.dageev.database.models.Bookings
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.*
import java.time.format.DateTimeFormatter

class BookingScheduler(
    private val telegramBot: TelegramBot,
    private val courtAPI: CourtAPI
) {
    private val logger = LoggerFactory.getLogger(BookingScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Запускает ежедневный планировщик
     * Каждый день в 23:57 загружает все бронирования на завтра и выполняет их в полночь
     */
    fun start() {
        logger.info("Starting booking scheduler...")

        scope.launch {
            while (isActive) {
                val now = LocalDateTime.now()
                val targetTime = now.toLocalDate().atTime(23, 57, 0)

                // Если уже прошло 23:57, планируем на завтра
                val nextRun = if (now.isAfter(targetTime)) {
                    targetTime.plusDays(1)
                } else {
                    targetTime
                }

                val delayMs = Duration.between(now, nextRun).toMillis()
                logger.info("Next scheduler run at: $nextRun (in ${delayMs / 1000} seconds)")

                delay(delayMs)

                // Запускаем процесс бронирования
                runBookingProcess()
            }
        }

        logger.info("Booking scheduler started successfully")
    }

    /**
     * Процесс бронирования:
     * 1. Загружает все бронирования на завтра
     * 2. Ждет до полуночи
     * 3. Выполняет все бронирования
     */
    private suspend fun runBookingProcess() {
        logger.info("Starting booking process...")

        try {
            // Получаем дату завтрашнего дня
            val tomorrow = LocalDate.now().plusDays(1)
            val tomorrowStr = tomorrow.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

            // Загружаем все бронирования на завтра
            val bookings = transaction {
                Booking.find {
                    (Bookings.courtDate eq tomorrowStr) and (Bookings.status eq "pending")
                }.toList()
            }

            if (bookings.isEmpty()) {
                logger.info("No bookings scheduled for tomorrow ($tomorrowStr)")
                return
            }

            logger.info("Found ${bookings.size} booking(s) for tomorrow ($tomorrowStr)")

            // Ждем до полуночи
            waitUntilMidnight()

            // Авторизуемся заранее
            val authSuccess = courtAPI.authenticate()
            if (!authSuccess) {
                logger.error("Authentication failed, cannot proceed with bookings")
                bookings.forEach { booking ->
                    updateBookingStatus(booking.id.value, "failed", "Authentication failed")
                    notifyUser(booking.userId, "Не удалось авторизоваться. Бронирование #${booking.id.value} не выполнено.")
                }
                return
            }

            // Выполняем все бронирования
            bookings.forEach { booking ->
                processBooking(booking)
            }

        } catch (e: Exception) {
            logger.error("Error in booking process", e)
        }
    }

    /**
     * Ждет до полуночи с точностью до миллисекунд
     */
    private suspend fun waitUntilMidnight() {
        val now = LocalDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val delayMs = Duration.between(now, midnight).toMillis()

        logger.info("Waiting until midnight (${delayMs}ms)...")
        delay(delayMs)
        logger.info("It's midnight! Starting bookings...")
    }

    /**
     * Обрабатывает одно бронирование
     */
    private suspend fun processBooking(booking: Booking) {
        logger.info("Processing booking #${booking.id.value}: date=${booking.courtDate}, time=${booking.courtTime}")

        try {
            val result = courtAPI.bookCourt(booking.courtDate, booking.courtTime)

            when (result) {
                is BookingResult.Success -> {
                    logger.info("Booking #${booking.id.value} successful")
                    updateBookingStatus(booking.id.value, "completed", result.message)
                    notifyUser(
                        booking.userId,
                        "Бронирование успешно!\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}"
                    )
                }

                is BookingResult.AlreadyBooked -> {
                    logger.warn("Booking #${booking.id.value} - court already booked")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "К сожалению, корт уже забронирован.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}"
                    )
                }

                is BookingResult.Error -> {
                    logger.error("Booking #${booking.id.value} failed: ${result.message}")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "Ошибка при бронировании.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}\nОшибка: ${result.message}"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Exception processing booking #${booking.id.value}", e)
            updateBookingStatus(booking.id.value, "failed", e.message ?: "Unknown error")
            notifyUser(
                booking.userId,
                "Произошла ошибка при бронировании #${booking.id.value}: ${e.message}"
            )
        }
    }

    /**
     * Обновляет статус бронирования в БД
     */
    private fun updateBookingStatus(bookingId: Int, status: String, message: String? = null) {
        transaction {
            val booking = Booking.findById(bookingId)
            booking?.status = status
            logger.info("Booking #$bookingId status updated to: $status${message?.let { " - $it" } ?: ""}")
        }
    }

    /**
     * Отправляет уведомление пользователю
     */
    private fun notifyUser(userId: Long, message: String) {
        try {
            telegramBot.sendMessage(userId, message)
        } catch (e: Exception) {
            logger.error("Failed to notify user $userId", e)
        }
    }

    /**
     * Останавливает планировщик
     */
    fun stop() {
        logger.info("Stopping booking scheduler...")
        scope.cancel()
    }
}
