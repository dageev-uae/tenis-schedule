package org.dageev.database

import org.dageev.database.models.Bookings
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URI

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    internal fun parsePostgresUrl(databaseUrl: String): Triple<String, String?, String?> {
        // Парсим URL формата: postgresql://username:password@host:port/database
        // Сначала парсим с оригинальной схемой postgresql://
        val uri = URI(databaseUrl)
        val userInfo = uri.userInfo?.split(":", limit = 2)  // limit=2 чтобы пароль с : не разбился
        val username = userInfo?.getOrNull(0)
        val password = userInfo?.getOrNull(1)

        // Собираем JDBC URL без credentials (они передаются отдельно)
        val port = if (uri.port == -1) 5432 else uri.port
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${port}${uri.path ?: "/postgres"}"

        return Triple(jdbcUrl, username, password)
    }

    fun init() {
        val databaseUrl = System.getenv("DATABASE_URL")

        if (databaseUrl != null) {
            // Production: PostgreSQL (Supabase/Railway)
            logger.info("Initializing PostgreSQL database...")

            val (jdbcUrl, username, password) = parsePostgresUrl(databaseUrl)
            logger.info("Connecting to: $jdbcUrl (user: $username)")

            Database.connect(
                url = jdbcUrl,
                driver = "org.postgresql.Driver",
                user = username ?: "",
                password = password ?: "",
                databaseConfig = DatabaseConfig {
                    useNestedTransactions = true
                }
            )
        } else {
            // Development: SQLite
            val databasePath = System.getenv("DATABASE_PATH") ?: "tennis_bot.db"
            logger.info("Initializing SQLite database at: $databasePath")
            Database.connect(
                url = "jdbc:sqlite:$databasePath",
                driver = "org.sqlite.JDBC"
            )
        }

        transaction {
            SchemaUtils.create(Bookings)
            logger.info("Database schema created successfully")
        }
    }
}
