# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Tennis Booking Bot - A Telegram bot that automatically books tennis courts at exactly midnight. Built with Kotlin, uses Exposed ORM for database operations.

## Build & Development Commands

### Building the Project

```bash
# Build the project
./gradlew build

# Build fat JAR with Shadow plugin (used for deployment)
./gradlew shadowJar

# Clean and rebuild
./gradlew clean build
```

### Running Locally

```bash
# Run the application directly
./gradlew run

# Or run the built JAR
java -jar build/libs/app.jar
```

### Docker

```bash
# Build Docker image
docker build -t tennis-bot .

# Run with environment variables
docker run --env-file .env tennis-bot
```

### Testing

```bash
# Run tests
./gradlew test
```

## Environment Configuration

The bot requires these environment variables (see `.env.example`):

- `TELEGRAM_BOT_TOKEN` - Telegram bot API token (required)
- `COURT_USERNAME` - Email for court booking site authentication (required)
- `COURT_PASSWORD` - Password for court booking site (required)
- `DATABASE_URL` - PostgreSQL connection string for production (format: `postgresql://user:pass@host:port/db`)
- `DATABASE_PATH` - SQLite database path for local development (defaults to `tennis_bot.db`)

**Database Selection**: The bot automatically uses PostgreSQL if `DATABASE_URL` is set, otherwise falls back to SQLite with `DATABASE_PATH`.

## Architecture

### Core Components

1. **Main.kt** - Application entry point
   - Initializes database via `DatabaseFactory.init()`
   - Creates `CourtAPI`, `TelegramBot`, and `BookingScheduler` instances
   - Starts bot polling and scheduler
   - Location: `src/main/kotlin/Main.kt`

2. **TelegramBot** (`org.dageev.bot.TelegramBot`)
   - Handles Telegram commands: `/start`, `/schedule`, `/list`, `/cancel`
   - Validates date (YYYY-MM-DD) format - time is not required
   - Creates bookings in database with status "pending"
   - Location: `src/main/kotlin/org/dageev/bot/TelegramBot.kt:15`

3. **BookingScheduler** (`org.dageev.scheduler.BookingScheduler`)
   - Checks every 5 minutes for pending bookings
   - **Execution logic**:
     - If court date is **less than 2 days away** → executes immediately
     - If court date is **exactly 2 days away** and time >= 23:57 → waits until midnight and executes
   - Examples (today = Oct 20):
     - Court date Oct 22 (2 days) → executes immediately if found before 23:57, at midnight if after 23:57
     - Court date Oct 21 (1 day) → executes immediately
     - Court date Oct 23 (3 days) → waits until midnight Oct 20→21
   - Updates booking status ("completed" or "failed") and notifies users via Telegram
   - Location: `src/main/kotlin/org/dageev/scheduler/BookingScheduler.kt:15`

4. **CourtAPI** (`org.dageev.court.CourtAPI`)
   - Authenticates with API and extracts `access_token` and `account_id` from nested JSON response
   - Sends booking requests with fixed parameters (booking_unit_id, amenity_id, amenity_slot_id, no_of_guest)
   - Returns `BookingResult` sealed class (Success, AlreadyBooked, Error)
   - Location: `src/main/kotlin/org/dageev/court/CourtAPI.kt:68`

5. **DatabaseFactory** (`org.dageev.database.DatabaseFactory`)
   - Initializes Exposed ORM with PostgreSQL (using HikariCP connection pool) or SQLite
   - Parses PostgreSQL connection URLs (handles `postgresql://` scheme)
   - Configures connection pool with proper prepared statement caching settings to prevent "prepared statement already exists" errors
   - Creates database schema on startup
   - Location: `src/main/kotlin/org/dageev/database/Database.kt:13`

### Data Model

**Booking** entity (`org.dageev.database.models.Booking`):
- `id` - Auto-increment primary key
- `userId` - Telegram user ID (Long)
- `courtDate` - Court booking date (String, format: YYYY-MM-DD)
- `courtTime` - Deprecated field, now stores empty string (time is fixed in API request)
- `createdAt` - Timestamp when booking was created
- `status` - Booking status: "pending", "completed", or "failed"

### Key Workflows

**Scheduling a Booking**:
1. User sends `/schedule 2025-10-25` to Telegram bot (only date required)
2. Bot validates date format (YYYY-MM-DD) in `TelegramBot.kt:67`
3. Creates new Booking record with status="pending" and empty courtTime in `TelegramBot.kt:77`
4. Bot confirms scheduling to user

**Executing Bookings**:
1. Scheduler checks every 5 minutes for pending bookings in `BookingScheduler.kt:32`
2. Calculates days difference between today and court date in `BookingScheduler.kt:66`
3. If difference < 2 days, executes immediately in `BookingScheduler.kt:70-73`
4. If difference == 2 days and time >= 23:57, waits until midnight in `BookingScheduler.kt:75-79`
5. Authenticates with API to get `access_token` and `account_id` at `BookingScheduler.kt:90`
6. Processes each booking via `processBooking()` which calls `CourtAPI.bookCourt(date)`
7. Updates booking status and notifies user

**Database Connection with HikariCP**:
- The bot parses `DATABASE_URL` format: `postgresql://username:password@host:port/database`
- Parsing logic in `DatabaseFactory.parsePostgresUrl()` at `Database.kt:14`
- Uses HikariCP connection pool with optimized settings:
  - `maximumPoolSize: 5`, `minimumIdle: 2`
  - Prepared statement caching disabled to prevent PostgreSQL "prepared statement already exists" errors
  - Connection parameters: `prepareThreshold=0&preparedStatementCacheQueries=0`
- Falls back to SQLite if `DATABASE_URL` is not set

## Deployment

The project is configured for Railway deployment with Supabase PostgreSQL. See `DEPLOYMENT.md` and `QUICKSTART.md` for detailed instructions.

### Railway Configuration
- Uses multi-stage Dockerfile with Gradle builder and JRE runtime
- Shadow JAR plugin creates fat JAR at `build/libs/app.jar`
- Entry point: `java -jar app.jar`
- Railway config in `railway.toml`

## Important Implementation Notes

- **Timing Precision**: The scheduler calculates millisecond-precise delays to ensure bookings happen exactly at midnight (see `BookingScheduler.kt:109-117`)
- **Coroutines**: Scheduler uses `kotlinx.coroutines` with `SupervisorJob` to prevent failures from crashing the scheduler
- **Authentication**: CourtAPI caches `access_token` and `account_id` in memory; re-authenticates if either is missing
- **API Integration**:
  - Authentication response has nested JSON structure: `data.party.access_token` and `data.party.account_id`
  - Booking request includes fixed parameters: `booking_unit_id`, `amenity_id`, `amenity_slot_id`, `no_of_guest=2`
  - Only `booking_date` and `account_id` vary per request
- **API Headers**: Court booking requires specific headers including `api-token`, `x-custom-identifier`, `origin`, and `authorization` with Bearer token
- **Database Connection Pool**: HikariCP is used for PostgreSQL with prepared statement caching disabled to prevent "prepared statement already exists" errors
- **Error Handling**: All booking failures are logged and users are notified via Telegram with error details
