package org.example.bot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import org.example.database.models.Booking
import org.example.database.models.Bookings
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TelegramBot(private val token: String) {
    private val logger = LoggerFactory.getLogger(TelegramBot::class.java)
    private lateinit var bot: Bot

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
                        /schedule <дата> <время> - запланировать бронирование
                          Пример: /schedule 2025-10-25 18:00
                        /list - показать все запланированные бронирования
                        /cancel <id> - отменить бронирование

                        Бронирование будет выполнено автоматически ровно в полночь!
                    """.trimIndent()

                    bot.sendMessage(
                        chatId = ChatId.fromId(message.chat.id),
                        text = welcomeMessage
                    )

                    logger.info("User $userId ($userName) started the bot")
                }

                command("schedule") {
                    val userId = message.from?.id?.toLong() ?: return@command
                    val args = args

                    if (args.size < 2) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный формат. Используйте: /schedule YYYY-MM-DD HH:MM\nПример: /schedule 2025-10-25 18:00"
                        )
                        return@command
                    }

                    val date = args[0]
                    val time = args[1]

                    // Валидация формата даты и времени
                    if (!isValidDate(date) || !isValidTime(time)) {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Неверный формат даты или времени. Используйте YYYY-MM-DD и HH:MM"
                        )
                        return@command
                    }

                    try {
                        val bookingId = transaction {
                            val booking = Booking.new {
                                this.userId = userId
                                this.courtDate = date
                                this.courtTime = time
                                this.createdAt = LocalDateTime.now()
                                this.status = "pending"
                            }
                            booking.id.value
                        }

                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Бронирование запланировано!\n\nID: $bookingId\nДата: $date\nВремя: $time\n\nБронирование будет выполнено автоматически в полночь."
                        )

                        logger.info("Booking scheduled: userId=$userId, date=$date, time=$time, bookingId=$bookingId")
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
