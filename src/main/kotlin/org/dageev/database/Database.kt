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

    private fun parsePostgresUrl(databaseUrl: String): Triple<String, String?, String?> {
        // Парсим URL формата: postgresql://username:password@host:port/database
        val uri = URI(databaseUrl.replace("postgresql://", "jdbc:postgresql://"))
        val userInfo = uri.userInfo?.split(":")
        val username = userInfo?.getOrNull(0)
        val password = userInfo?.getOrNull(1)

        // Собираем JDBC URL без credentials (они передаются отдельно)
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}"

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
