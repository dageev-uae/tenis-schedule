package org.dageev.bot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import kotlinx.coroutines.runBlocking
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
import java.time.format.DateTimeFormatter

class TelegramBot(private val token: String, private val courtAPI: CourtAPI) {
    private val logger = LoggerFactory.getLogger(TelegramBot::class.java)
    private lateinit var bot: Bot
    private val dubaiZone = ZoneId.of("Asia/Dubai") // UTC+4

    fun start() {
        logger.info("Starting Telegram bot...")

        bot = bot {
            this.token = this@TelegramBot.token

            dispatch {
                command("start") {
                    val userId = message.from?.id ?: return@command
                    val userName = message.from?.firstName ?: "User"

                    val welcomeMessage = """
                        Привет, $userName!

                        Я бот для автоматического бронирования теннисных кортов.

                        Доступные команды:
                        /schedule <дата> <время> <корт> - запланировать бронирование
                          Пример: /schedule 2025-10-25 06:00 3
                        /list - показать все запланированные бронирования
                        /cancel <id> - отменить бронирование
                        /test_auth - проверить подключение к системе бронирования

                        Корты: 3 или 4
                        Бронирование будет выполнено автоматически ровно в полночь!
                    """.trimIndent()

                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = welcomeMessage
                    )

                    logger.info("User $userId ($userName) started the bot")
                }

                command("schedule") {
                    val userId = message.from?.id ?: return@command
                    val args = args

                    if (args.size < 3) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный формат. Используйте: /schedule YYYY-MM-DD HH:MM КОРТ\nПример: /schedule 2025-10-25 06:00 3"
                        )
                        return@command
                    }

                    val date = args[0]
                    val time = args[1]
                    val courtNumberArg = args[2].toIntOrNull()

                    // Валидация формата даты
                    if (!isValidDate(date)) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный формат даты. Используйте YYYY-MM-DD"
                        )
                        return@command
                    }

                    // Валидация формата времени
                    if (!isValidTime(time)) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный формат времени. Используйте HH:MM (например, 06:00)"
                        )
                        return@command
                    }

                    // Валидация номера корта
                    if (courtNumberArg == null || courtNumberArg !in courtAPI.courtMapping) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный номер корта. Доступные корты: ${courtAPI.courtMapping.keys.joinToString(", ")}"
                        )
                        return@command
                    }

                    val amenityId = courtAPI.courtMapping[courtNumberArg]!!

                    // Ищем slot_id в БД
                    val slotId = transaction {
                        AmenitySlot.find {
                            (AmenitySlots.amenityId eq amenityId) and (AmenitySlots.startTime eq time)
                        }.firstOrNull()?.id?.value
                    }

                    if (slotId == null) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Слот на время $time для корта $courtNumberArg не найден в базе. Возможно, слоты ещё не загружены."
                        )
                        return@command
                    }

                    try {
                        // Вычисляем разницу дней до бронирования (по времени Dubai)
                        val todayDubai = ZonedDateTime.now(dubaiZone).toLocalDate()
                        val courtDate = LocalDate.parse(date)
                        val daysDiff = Duration.between(todayDubai.atStartOfDay(), courtDate.atStartOfDay()).toDays()

                        if (daysDiff <= 2) {
                            // Бронируем сразу, без записи в БД (избегаем гонки со scheduler)
                            logger.info("Immediate booking: userId=$userId, date=$date, time=$time, court=$courtNumberArg, daysDiff=$daysDiff")

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Начинаю бронирование...\n\nДата: $date\nВремя: $time\nКорт: $courtNumberArg"
                            )

                            // Выполняем бронирование
                            val result = runBlocking {
                                // Авторизуемся
                                val authSuccess = courtAPI.authenticate()
                                if (!authSuccess) {
                                    return@runBlocking BookingResult.Error("Не удалось авторизоваться")
                                }

                                // Бронируем корт
                                courtAPI.bookCourt(date, slotId, amenityId)
                            }

                            val responseMessage = when (result) {
                                is BookingResult.Success -> {
                                    logger.info("Immediate booking successful: userId=$userId, date=$date, time=$time, court=$courtNumberArg")
                                    "✅ Бронирование успешно!\n\nДата: $date\nВремя: $time\nКорт: $courtNumberArg\n\n${result.message}"
                                }
                                is BookingResult.AlreadyBooked -> {
                                    logger.warn("Immediate booking - court already booked: userId=$userId, date=$date")
                                    "⚠️ К сожалению, корт уже забронирован.\n\nДата: $date\nВремя: $time\nКорт: $courtNumberArg\n\n${result.message}"
                                }
                                is BookingResult.Error -> {
                                    logger.error("Immediate booking failed: userId=$userId, date=$date, error=${result.message}")
                                    "❌ Ошибка при бронировании.\n\nДата: $date\nВремя: $time\nКорт: $courtNumberArg\n\nОшибка: ${result.message}"
                                }
                            }

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = responseMessage
                            )

                        } else {
                            // Сохраняем в БД для отложенного бронирования через scheduler
                            val bookingId = transaction {
                                val booking = Booking.new {
                                    this.userId = userId
                                    this.courtDate = date
                                    this.courtTime = time
                                    this.courtNumber = courtNumberArg
                                    this.createdAt = LocalDateTime.now()
                                    this.status = "pending"
                                }
                                booking.id.value
                            }

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Бронирование запланировано!\n\nID: $bookingId\nДата: $date\nВремя: $time\nКорт: $courtNumberArg\n\nБронирование будет выполнено автоматически в полночь (по времени Dubai)."
                            )

                            logger.info("Booking scheduled: userId=$userId, date=$date, time=$time, court=$courtNumberArg, bookingId=$bookingId, daysDiff=$daysDiff")
                        }

                    } catch (e: Exception) {
                        logger.error("Error scheduling booking", e)
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Произошла ошибка при планировании бронирования: ${e.message}"
                        )
                    }
                }

                command("list") {
                    val userId = message.from?.id?.toLong() ?: return@command

                    try {
                        val bookings = transaction {
                            Booking.find { Bookings.userId eq userId }
                                .filter { it.status == "pending" }
                                .toList()
                        }

                        if (bookings.isEmpty()) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "У вас нет запланированных бронирований."
                            )
                        } else {
                            val bookingsList = bookings.joinToString("\n\n") { booking ->
                                "ID: ${booking.id.value}\n" +
                                        "Дата: ${booking.courtDate}\n" +
                                        "Время: ${booking.courtTime}\n" +
                                        "Корт: ${booking.courtNumber}\n" +
                                        "Статус: ${booking.status}"
                            }

                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Ваши запланированные бронирования:\n\n$bookingsList"
                            )
                        }

                        logger.info("User $userId requested booking list")
                    } catch (e: Exception) {
                        logger.error("Error listing bookings", e)
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Произошла ошибка при получении списка бронирований: ${e.message}"
                        )
                    }
                }

                command("cancel") {
                    val userId = message.from?.id?.toLong() ?: return@command
                    val args = args

                    if (args.isEmpty()) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Укажите ID бронирования. Используйте: /cancel <id>"
                        )
                        return@command
                    }

                    val bookingId = args[0].toIntOrNull()
                    if (bookingId == null) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный ID бронирования."
                        )
                        return@command
                    }

                    try {
                        val deleted = transaction {
                            val booking = Booking.findById(bookingId)
                            if (booking != null && booking.userId == userId) {
                                booking.delete()
                                true
                            } else {
                                false
                            }
                        }

                        if (deleted) {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Бронирование #$bookingId отменено."
                            )
                            logger.info("Booking cancelled: userId=$userId, bookingId=$bookingId")
                        } else {
                            bot.sendMessage(
                                chatId = ChatId.fromId(message.chat.id),
                                text = "Бронирование не найдено или не принадлежит вам."
                            )
                        }
                    } catch (e: Exception) {
                        logger.error("Error cancelling booking", e)
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Произошла ошибка при отмене бронирования: ${e.message}"
                        )
                    }
                }

                command("test_auth") {
                    val chatId = ChatId.fromId(message.chat.id)

                    bot.sendMessage(
                        chatId = chatId,
                        text = "Тестирую аутентификацию..."
                    )

                    try {
                        val success = runBlocking {
                            courtAPI.authenticate()
                        }

                        val responseText = if (success) {
                            "✅ Аутентификация успешна!"
                        } else {
                            "❌ Ошибка аутентификации. Проверьте логи для деталей."
                        }

                        bot.sendMessage(
                            chatId = chatId,
                            text = responseText
                        )

                        logger.info("Test auth command executed, result: $success")
                    } catch (e: Exception) {
                        logger.error("Error testing authentication", e)
                        bot.sendMessage(
                            chatId = chatId,
                            text = "Произошла ошибка при тестировании: ${e.message}"
                        )
                    }
                }
            }
        }

        bot.startPolling()
        logger.info("Telegram bot started successfully")
    }

    fun sendMessage(chatId: Long, message: String) {
        bot.sendMessage(ChatId.fromId(chatId), message)
    }

    private fun isValidDate(date: String): Boolean {
        return try {
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            java.time.LocalDate.parse(date, formatter)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isValidTime(time: String): Boolean {
        return try {
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            java.time.LocalTime.parse(time, formatter)
            true
        } catch (e: Exception) {
            false
        }
    }
}
