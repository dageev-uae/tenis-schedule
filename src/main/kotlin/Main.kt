package org.dageev

import org.dageev.bot.TelegramBot
import org.dageev.court.CourtAPI
import org.dageev.database.DatabaseFactory
import org.dageev.scheduler.BookingScheduler
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Main")

    logger.info("Starting Tennis Booking Bot...")

    // Проверяем обязательные переменные окружения
    val botToken = System.getenv("TELEGRAM_BOT_TOKEN")
    if (botToken.isNullOrEmpty()) {
        logger.error("TELEGRAM_BOT_TOKEN environment variable is not set")
        return
    }

    try {
        // Инициализируем базу данных
        DatabaseFactory.init()
        logger.info("Database initialized successfully")

        // Создаем экземпляры компонентов
        val courtAPI = CourtAPI()
        val telegramBot = TelegramBot(botToken, courtAPI)
        val scheduler = BookingScheduler(telegramBot, courtAPI)

        // Запускаем Telegram бота
        telegramBot.start()

        // Запускаем планировщик
        scheduler.start()

        logger.info("Tennis Booking Bot started successfully!")
        logger.info("Bot is now running. Press Ctrl+C to stop.")

        // Поддерживаем работу приложения
        Thread.currentThread().join()

    } catch (e: Exception) {
        logger.error("Fatal error starting the bot", e)
    }
}