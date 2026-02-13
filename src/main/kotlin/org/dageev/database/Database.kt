package org.dageev.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.dageev.database.models.AmenitySlots
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
        // Добавляем параметры для предотвращения ошибок с prepared statements
        val port = if (uri.port == -1) 5432 else uri.port
        val path = uri.path?.ifEmpty { "/postgres" } ?: "/postgres"
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${port}${path}?prepareThreshold=0&preparedStatementCacheQueries=0"

        return Triple(jdbcUrl, username, password)
    }

    fun init() {
        val databaseUrl = System.getenv("DATABASE_URL")

        if (databaseUrl != null) {
            // Production: PostgreSQL (Supabase/Railway) с HikariCP
            logger.info("Initializing PostgreSQL database with HikariCP...")

            val (jdbcUrl, username, password) = parsePostgresUrl(databaseUrl)
            logger.info("Connecting to: $jdbcUrl (user: $username)")

            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.driverClassName = "org.postgresql.Driver"
                this.username = username ?: ""
                this.password = password ?: ""

                // Connection pool settings
                maximumPoolSize = 5
                minimumIdle = 2
                idleTimeout = 300000 // 5 minutes
                connectionTimeout = 10000 // 10 seconds
                maxLifetime = 600000 // 10 minutes

                // PostgreSQL specific settings для предотвращения ошибок prepared statements
                addDataSourceProperty("cachePrepStmts", "false")
                addDataSourceProperty("prepareThreshold", "0")
                addDataSourceProperty("preparedStatementCacheQueries", "0")
            }

            val dataSource = HikariDataSource(config)

            Database.connect(
                datasource = dataSource,
                databaseConfig = DatabaseConfig {
                    useNestedTransactions = false
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
            SchemaUtils.createMissingTablesAndColumns(Bookings, AmenitySlots)
            logger.info("Database schema created successfully")
        }
    }
}
