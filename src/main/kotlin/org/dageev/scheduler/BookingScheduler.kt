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

class BookingScheduler(
    private val telegramBot: TelegramBot,
    private val courtAPI: CourtAPI
) {
    private val logger = LoggerFactory.getLogger(BookingScheduler::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dubaiZone = ZoneId.of("Asia/Dubai") // UTC+4

    @Volatile
    private var slotFetchComplete = false

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
     * Одноразовый fetch слотов в полночь по Дубаю.
     * Ждёт до полуночи, затем делает запрос на слоты для обоих кортов на указанную дату.
     */
    fun scheduleSlotFetch(targetDate: String) {
        scope.launch {
            try {
                logger.info("Scheduling slot fetch for $targetDate at midnight Dubai time")
                waitUntilMidnight()

                logger.info("Midnight reached! Fetching slots for $targetDate")

                // Авторизуемся
                val authSuccess = courtAPI.authenticate()
                if (!authSuccess) {
                    logger.error("Authentication failed, cannot fetch slots")
                    notifyAdmin("Ошибка при загрузке слотов на $targetDate: не удалось авторизоваться")
                    return@launch
                }

                // Fetch слотов для обоих кортов
                var totalSaved = 0
                val slotsByCourt = mutableMapOf<Int, MutableList<String>>()

                for ((courtNumber, amenityId) in courtAPI.courtMapping) {
                    val slots = courtAPI.fetchSlots(targetDate, amenityId)
                    logger.info("Court $courtNumber: fetched ${slots.size} slots")

                    val courtSlots = mutableListOf<String>()
                    transaction {
                        for (slot in slots) {
                            val normalizedStartTime = normalizeTime(slot.start_time)
                            val normalizedEndTime = normalizeTime(slot.end_time)

                            // Upsert: если слот с таким id уже есть — пропускаем
                            val existing = AmenitySlot.findById(slot.id)
                            if (existing == null) {
                                AmenitySlot.new(slot.id) {
                                    this.amenityId = amenityId
                                    this.startTime = normalizedStartTime
                                    this.endTime = normalizedEndTime
                                }
                                totalSaved++
                            } else {
                                logger.info("Slot ${slot.id} already exists, skipping")
                            }
                            courtSlots.add("$normalizedStartTime-$normalizedEndTime")
                        }
                    }
                    slotsByCourt[courtNumber] = courtSlots
                }

                logger.info("Slot fetch completed: saved $totalSaved new slots for $targetDate")
                slotFetchComplete = true

                // Уведомляем админа о загруженных слотах
                notifySlotsFetched(targetDate, slotsByCourt, totalSaved)

            } catch (e: Exception) {
                logger.error("Error during slot fetch", e)
                slotFetchComplete = true // Помечаем как завершённый, чтобы не блокировать бронирования навечно
                notifyAdmin("Ошибка при загрузке слотов на $targetDate: ${e.message}")
            }
        }
    }

    /**
     * Нормализует время в формат HH:MM (с ведущим нулём)
     * "6:00" -> "06:00", "06:00" -> "06:00", "6:00:00" -> "06:00"
     */
    private fun normalizeTime(time: String): String {
        val parts = time.split(":")
        if (parts.size >= 2) {
            val hour = parts[0].trim().padStart(2, '0')
            val minute = parts[1].trim().padStart(2, '0')
            return "$hour:$minute"
        }
        return time
    }

    /**
     * Обрабатывает все pending бронирования
     */
    private suspend fun processAllPendingBookings() {
        // Используем время Dubai (UTC+4)
        val nowDubai = ZonedDateTime.now(dubaiZone)
        val today = nowDubai.toLocalDate()

        // Загружаем все pending бронирования
        val allBookings = transaction {
            Booking.find { Bookings.status eq "pending" }.toList()
        }

        if (allBookings.isEmpty()) {
            return
        }

        logger.info("Checking ${allBookings.size} pending booking(s) at Dubai time: ${nowDubai.toLocalTime()}")

        allBookings.forEach { booking ->
            val courtDate = LocalDate.parse(booking.courtDate)
            val daysDiff = Duration.between(today.atStartOfDay(), courtDate.atStartOfDay()).toDays()

            when {
                // Если до даты корта меньше 2 дней - выполняем сразу
                daysDiff <= 2 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is less than 2 days away (${daysDiff} days) - executing immediately")
                    // Ждём завершения slot fetch если он запланирован и ещё не завершён
                    waitForSlotFetchIfNeeded()
                    executeBookings(listOf(booking))
                }
                // Если ровно 3 дня и уже после 23:57 (по времени Dubai) - ждем полуночи
                daysDiff == 3L && nowDubai.hour >= 23 && nowDubai.minute >= 57 -> {
                    logger.info("Booking #${booking.id.value} for $courtDate is exactly 3 days away and it's after 23:57 Dubai time - waiting until midnight")
                    notifyUser(booking.userId, "До даты бронирование 3 дня. Сделаем бронирование #${booking.id.value} через несколько минут.")
                    waitUntilMidnight()
                    // Ждём завершения slot fetch после полуночи
                    waitForSlotFetchIfNeeded()
                    executeBookings(listOf(booking))
                }
            }
        }
    }

    /**
     * Ждёт завершения slot fetch (если он был запланирован), максимум 30 секунд
     */
    private suspend fun waitForSlotFetchIfNeeded() {
        if (slotFetchComplete) return

        logger.info("Waiting for slot fetch to complete...")
        var waited = 0
        while (!slotFetchComplete && waited < 30000) {
            delay(500)
            waited += 500
        }
        if (!slotFetchComplete) {
            logger.warn("Slot fetch did not complete within 30 seconds, proceeding anyway")
        } else {
            logger.info("Slot fetch completed, proceeding with bookings")
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
     * Ждет до полуночи по времени Dubai с точностью до миллисекунд
     */
    private suspend fun waitUntilMidnight() {
        val nowDubai = ZonedDateTime.now(dubaiZone)
        val midnightDubai = nowDubai.toLocalDate().plusDays(1).atStartOfDay(dubaiZone)
        val delayMs = Duration.between(nowDubai, midnightDubai).toMillis()

        logger.info("Waiting until midnight Dubai time (${delayMs}ms)... Current time: ${nowDubai.toLocalTime()}")
        delay(delayMs)
        logger.info("It's midnight in Dubai! Starting...")
    }

    /**
     * Обрабатывает одно бронирование
     */
    private suspend fun processBooking(booking: Booking) {
        logger.info("Processing booking #${booking.id.value}: date=${booking.courtDate}, time=${booking.courtTime}, court=${booking.courtNumber}")

        try {
            val amenityId = courtAPI.courtMapping[booking.courtNumber]
            if (amenityId == null) {
                updateBookingStatus(booking.id.value, "failed", "Unknown court number: ${booking.courtNumber}")
                notifyUser(booking.userId, "Неизвестный номер корта: ${booking.courtNumber}. Бронирование #${booking.id.value} не выполнено.")
                return
            }

            // Ищем slot_id в БД
            val slotId = transaction {
                AmenitySlot.find {
                    (AmenitySlots.amenityId eq amenityId) and (AmenitySlots.startTime eq booking.courtTime)
                }.firstOrNull()?.id?.value
            }

            if (slotId == null) {
                updateBookingStatus(booking.id.value, "failed", "Slot not found for time=${booking.courtTime}, court=${booking.courtNumber}")
                notifyUser(booking.userId, "Слот на время ${booking.courtTime} для корта ${booking.courtNumber} не найден. Бронирование #${booking.id.value} не выполнено.")
                return
            }

            val result = courtAPI.bookCourt(booking.courtDate, slotId, amenityId)

            when (result) {
                is BookingResult.Success -> {
                    logger.info("Booking #${booking.id.value} successful")
                    updateBookingStatus(booking.id.value, "completed", result.message)
                    notifyUser(
                        booking.userId,
                        "Бронирование успешно!\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}\nКорт: ${booking.courtNumber}"
                    )
                }

                is BookingResult.AlreadyBooked -> {
                    logger.warn("Booking #${booking.id.value} - court already booked")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "К сожалению, корт уже забронирован.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}\nКорт: ${booking.courtNumber}"
                    )
                }

                is BookingResult.Error -> {
                    logger.error("Booking #${booking.id.value} failed: ${result.message}")
                    updateBookingStatus(booking.id.value, "failed", result.message)
                    notifyUser(
                        booking.userId,
                        "Ошибка при бронировании.\n\nID: ${booking.id.value}\nДата: ${booking.courtDate}\nВремя: ${booking.courtTime}\nКорт: ${booking.courtNumber}\nОшибка: ${result.message}"
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
     * Отправляет сообщение админу (chatId из /sendme)
     */
    private fun notifyAdmin(message: String) {
        val chatId = telegramBot.adminChatId ?: return
        notifyUser(chatId, message)
    }

    /**
     * Формирует и отправляет сообщение о загруженных слотах
     */
    private fun notifySlotsFetched(targetDate: String, slotsByCourt: Map<Int, List<String>>, totalSaved: Int) {
        val chatId = telegramBot.adminChatId ?: return

        val message = buildString {
            appendLine("Слоты загружены на $targetDate:")
            appendLine()
            for ((courtNumber, slots) in slotsByCourt.toSortedMap()) {
                appendLine("Корт $courtNumber:")
                if (slots.isEmpty()) {
                    appendLine("  нет слотов")
                } else {
                    slots.forEach { appendLine("  $it") }
                }
                appendLine()
            }
            append("Всего сохранено: $totalSaved новых слотов")
        }

        notifyUser(chatId, message)
    }

    /**
     * Останавливает планировщик
     */
    fun stop() {
        logger.info("Stopping booking scheduler...")
        scope.cancel()
    }
}
