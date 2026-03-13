package org.dageev.scheduler

import kotlinx.coroutines.*
import org.dageev.bot.TelegramBot
import org.dageev.court.BookingResult
import org.dageev.court.CourtAPI
import org.dageev.database.models.AmenitySlot
import org.dageev.database.models.AmenitySlots
import org.dageev.database.models.Booking
import org.dageev.database.models.Bookings
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.*

/**
 * Все данные для бронирования, подготовленные заранее (до полуночи).
 * В момент полуночи остаётся только дёрнуть courtAPI.bookCourt().
 */
private data class PreparedBooking(
    val bookingId: Int,
    val userId: Long,
    val courtDate: String,
    val courtTime: String,
    val courtNumber: Int,
    val slotId: String,
    val amenityId: String
)

class BookingScheduler(
    private val telegramBot: TelegramBot,
    private val courtAPI: CourtAPI
) {
    private val logger = LoggerFactory.getLogger(BookingScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dubaiZone = ZoneId.of("Asia/Dubai") // UTC+4

    @Volatile
    private var midnightWaitInProgress = false

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

                // Проверяем каждые 30 секунд
                delay(30 * 1000L)
            }
        }

        logger.info("Booking scheduler started successfully")
    }

    /**
     * Обрабатывает все pending бронирования
     */
    private suspend fun processAllPendingBookings() {
        // Загружаем все pending бронирования
        val allBookings = transaction {
            Booking.find { Bookings.status eq "pending" }.toList()
        }

        if (allBookings.isEmpty()) {
            return
        }

        val nowDubai = ZonedDateTime.now(dubaiZone)
        val today = nowDubai.toLocalDate()

        logger.info("Checking ${allBookings.size} pending booking(s) at Dubai time: ${nowDubai.toLocalTime()}")

        // Разделяем бронирования на немедленные и полуночные
        val immediateBookings = mutableListOf<Booking>()
        val midnightBookings = mutableListOf<Booking>()

        allBookings.forEach { booking ->
            val courtDate = LocalDate.parse(booking.courtDate)
            val daysDiff = Duration.between(today.atStartOfDay(), courtDate.atStartOfDay()).toDays()

            when {
                daysDiff <= 2 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is $daysDiff days away - will execute immediately")
                    immediateBookings.add(booking)
                }
                daysDiff == 3L && nowDubai.hour >= 23 && nowDubai.minute >= 57 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is 3 days away and it's after 23:57 - will execute at midnight")
                    midnightBookings.add(booking)
                }
            }
        }

        // Немедленные бронирования — параллельно
        if (immediateBookings.isNotEmpty()) {
            executeBookings(immediateBookings)
        }

        // Полуночные бронирования — готовим всё заранее, ждём полночь, стреляем параллельно
        if (midnightBookings.isNotEmpty()) {
            // Защита от повторного входа (следующий цикл через 30 сек может попасть сюда снова)
            if (midnightWaitInProgress) {
                logger.info("Midnight wait already in progress, skipping")
                return
            }
            midnightWaitInProgress = true

            try {
                midnightBookings.forEach { booking ->
                    notifyUser(booking.userId, "До даты бронирование 3 дня. Сделаем бронирование #${booking.id.value} через несколько минут.")
                }

                // 1. Подготавливаем все данные ДО полуночи (DB lookup, amenityId)
                val prepared = prepareBookings(midnightBookings)
                if (prepared.isEmpty()) {
                    logger.warn("No bookings could be prepared, nothing to execute at midnight")
                    return
                }

                // 2. Авторизуемся ЗАРАНЕЕ, до полуночи
                val authSuccess = courtAPI.authenticate()
                if (!authSuccess) {
                    logger.error("Pre-authentication failed")
                    prepared.forEach { pb ->
                        updateBookingStatus(pb.bookingId, "failed", "Pre-authentication failed")
                        notifyUser(pb.userId, "Не удалось авторизоваться заранее. Бронирование #${pb.bookingId} не выполнено.")
                    }
                    return
                }
                logger.info("All prepared: ${prepared.size} booking(s), token ready. Waiting until midnight...")

                // 3. Ждём полночь
                waitUntilMidnight()

                // 4. СРАЗУ после полуночи — только HTTP-запросы, параллельно
                fireBookingsInParallel(prepared)
            } finally {
                midnightWaitInProgress = false
            }
        }
    }

    /**
     * Выполняет группу бронирований (с авторизацией) — для немедленных бронирований
     */
    private suspend fun executeBookings(bookings: List<Booking>) {
        logger.info("Executing ${bookings.size} booking(s)")

        val authSuccess = courtAPI.authenticate()
        if (!authSuccess) {
            logger.error("Authentication failed, cannot proceed with bookings")
            bookings.forEach { booking ->
                updateBookingStatus(booking.id.value, "failed", "Authentication failed")
                notifyUser(booking.userId, "Не удалось авторизоваться. Бронирование #${booking.id.value} не выполнено.")
            }
            return
        }

        val prepared = prepareBookings(bookings)
        fireBookingsInParallel(prepared)
    }

    /**
     * Подготавливает все данные для бронирований заранее (amenityId, slotId из БД).
     * Бронирования с ошибками помечаются как failed и исключаются из результата.
     */
    private fun prepareBookings(bookings: List<Booking>): List<PreparedBooking> {
        return bookings.mapNotNull { booking ->
            val amenityId = courtAPI.courtMapping[booking.courtNumber]
            if (amenityId == null) {
                updateBookingStatus(booking.id.value, "failed", "Unknown court number: ${booking.courtNumber}")
                notifyUser(booking.userId, "Неизвестный номер корта: ${booking.courtNumber}. Бронирование #${booking.id.value} не выполнено.")
                return@mapNotNull null
            }

            val slotId = transaction {
                AmenitySlot.find {
                    (AmenitySlots.amenityId eq amenityId) and (AmenitySlots.startTime eq booking.courtTime)
                }.firstOrNull()?.id?.value
            }

            if (slotId == null) {
                updateBookingStatus(booking.id.value, "failed", "Slot not found for time=${booking.courtTime}, court=${booking.courtNumber}")
                notifyUser(booking.userId, "Слот на время ${booking.courtTime} для корта ${booking.courtNumber} не найден. Бронирование #${booking.id.value} не выполнено.")
                return@mapNotNull null
            }

            logger.info("Prepared booking #${booking.id.value}: date=${booking.courtDate}, slot=$slotId, amenity=$amenityId")
            PreparedBooking(
                bookingId = booking.id.value,
                userId = booking.userId,
                courtDate = booking.courtDate,
                courtTime = booking.courtTime,
                courtNumber = booking.courtNumber,
                slotId = slotId,
                amenityId = amenityId
            )
        }
    }

    /**
     * Стреляет все подготовленные бронирования параллельно.
     * Никаких DB-запросов или авторизации — только courtAPI.bookCourt().
     */
    private suspend fun fireBookingsInParallel(prepared: List<PreparedBooking>) {
        logger.info("Firing ${prepared.size} booking(s) in parallel")
        coroutineScope {
            prepared.map { pb ->
                async {
                    firePreparedBooking(pb)
                }
            }.awaitAll()
        }
    }

    /**
     * Выполняет один подготовленный запрос бронирования — только HTTP-вызов и обработка результата.
     */
    private suspend fun firePreparedBooking(pb: PreparedBooking) {
        try {
            val result = courtAPI.bookCourt(pb.courtDate, pb.slotId, pb.amenityId)

            when (result) {
                is BookingResult.Success -> {
                    logger.info("Booking #${pb.bookingId} successful")
                    updateBookingStatus(pb.bookingId, "completed", result.message)
                    notifyUser(pb.userId, "Бронирование успешно!\n\nID: ${pb.bookingId}\nДата: ${pb.courtDate}\nВремя: ${pb.courtTime}\nКорт: ${pb.courtNumber}")
                }
                is BookingResult.AlreadyBooked -> {
                    logger.warn("Booking #${pb.bookingId} - court already booked")
                    updateBookingStatus(pb.bookingId, "failed", result.message)
                    notifyUser(pb.userId, "К сожалению, корт уже забронирован.\n\nID: ${pb.bookingId}\nДата: ${pb.courtDate}\nВремя: ${pb.courtTime}\nКорт: ${pb.courtNumber}")
                }
                is BookingResult.Error -> {
                    logger.error("Booking #${pb.bookingId} failed: ${result.message}")
                    updateBookingStatus(pb.bookingId, "failed", result.message)
                    notifyUser(pb.userId, "Ошибка при бронировании.\n\nID: ${pb.bookingId}\nДата: ${pb.courtDate}\nВремя: ${pb.courtTime}\nКорт: ${pb.courtNumber}\nОшибка: ${result.message}")
                }
            }
        } catch (e: Exception) {
            logger.error("Exception firing booking #${pb.bookingId}", e)
            updateBookingStatus(pb.bookingId, "failed", e.message ?: "Unknown error")
            notifyUser(pb.userId, "Произошла ошибка при бронировании #${pb.bookingId}: ${e.message}")
        }
    }

    /**
     * Ждет до полуночи по времени Dubai с точностью до миллисекунд
     */
    private suspend fun waitUntilMidnight() {
        val nowDubai = ZonedDateTime.now(dubaiZone)
        val midnightDubai = nowDubai.toLocalDate().plusDays(1).atStartOfDay(dubaiZone)
        val delayMs = Duration.between(nowDubai, midnightDubai).toMillis()

        logger.info("Waiting until midnight Dubai time (${delayMs}ms)... Current time: ${nowDubai.toLocalTime()}")
        delay(delayMs)
        logger.info("It's midnight in Dubai! Starting booking immediately...")
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