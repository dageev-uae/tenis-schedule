package org.dageev.scheduler

import kotlinx.coroutines.*
import org.dageev.bot.TelegramBot
import org.dageev.court.BookingResult
import org.dageev.court.CourtAPI
import org.dageev.database.models.Booking
import org.dageev.database.models.Bookings
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.*

class BookingScheduler(
    private val telegramBot: TelegramBot,
    private val courtAPI: CourtAPI
) {
    private val logger = LoggerFactory.getLogger(BookingScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Запускает планировщик бронирований
     * Проверяет каждые 5 минут, есть ли бронирования, которые нужно выполнить
     */
    fun start() {
        logger.info("Starting booking scheduler...")

        scope.launch {
            while (isActive) {
                try {
                    processAllPendingBookings()
                } catch (e: Exception) {
                    logger.error("Error processing bookings", e)
                }

                // Проверяем каждые 5 минут
                delay(5 * 60 * 1000L)
            }
        }

        logger.info("Booking scheduler started successfully")
    }

    /**
     * Обрабатывает все pending бронирования
     * Логика:
     * - Если до даты корта меньше 2 дней - выполняем сразу
     * - Если 2 дня или больше - выполняем в полночь за 2 дня до даты корта
     */
    private suspend fun processAllPendingBookings() {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        // Загружаем все pending бронирования
        val allBookings = transaction {
            Booking.find { Bookings.status eq "pending" }.toList()
        }

        if (allBookings.isEmpty()) {
            return
        }

        logger.info("Checking ${allBookings.size} pending booking(s)")

        allBookings.forEach { booking ->
            val courtDate = LocalDate.parse(booking.courtDate)
            val daysDiff = Duration.between(today.atStartOfDay(), courtDate.atStartOfDay()).toDays()

            when {
                // Если до даты корта меньше 2 дней - выполняем сразу
                daysDiff < 2 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is less than 2 days away (${daysDiff} days) - executing immediately")
                    executeBookings(listOf(booking))
                }
                // Если ровно 2 дня и уже после 23:57 - ждем полуночи
                daysDiff == 2L && now.hour >= 23 && now.minute >= 57 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is exactly 2 days away - waiting until midnight")
                    waitUntilMidnight()
                    executeBookings(listOf(booking))
                }
            }
        }
    }

    /**
     * Выполняет группу бронирований
     */
    private suspend fun executeBookings(bookings: List<Booking>) {
        logger.info("Executing ${bookings.size} booking(s)")

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
        logger.info("Processing booking #${booking.id.value}: date=${booking.courtDate}")

        try {
            val result = courtAPI.bookCourt(booking.courtDate)

            when (result) {
                is BookingResult.Success -> {
                    logger.info("Booking #${booking.id.value} successful")
                    updateBookingStatus(booking.id.value, "completed", result.message)
                    notifyUser(
                        booking.userId,
                        "Бронирование успешно!\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}"
                    )
                }

                is BookingResult.AlreadyBooked -> {
                    logger.warn("Booking #${booking.id.value} - court already booked")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "К сожалению, корт уже забронирован.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}"
                    )
                }

                is BookingResult.Error -> {
                    logger.error("Booking #${booking.id.value} failed: ${result.message}")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "Ошибка при бронировании.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nОшибка: ${result.message}"
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
