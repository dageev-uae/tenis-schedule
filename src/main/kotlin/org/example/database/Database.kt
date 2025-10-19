package org.example.database

import org.example.database.models.Bookings
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object DatabaseFactory {
    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init() {
        val databaseUrl = System.getenv("DATABASE_URL")

        if (databaseUrl != null) {
            // Production: PostgreSQL (Supabase/Railway)
            logger.info("Initializing PostgreSQL database...")
            Database.connect(
                url = databaseUrl,
                driver = "org.postgresql.Driver"
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
