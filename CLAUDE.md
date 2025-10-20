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
   - Validates date (YYYY-MM-DD) and time (HH:MM) formats
   - Creates bookings in database with status "pending"
   - Location: `src/main/kotlin/org/dageev/bot/TelegramBot.kt:15`

3. **BookingScheduler** (`org.dageev.scheduler.BookingScheduler`)
   - Runs daily at 23:57 to prepare for midnight bookings
   - Loads all "pending" bookings for the next day
   - Waits until exactly midnight (00:00:00.000)
   - Executes bookings and updates their status ("completed" or "failed")
   - Notifies users of booking results via Telegram
   - Location: `src/main/kotlin/org/dageev/scheduler/BookingScheduler.kt:15`

4. **CourtAPI** (`org.dageev.court.CourtAPI`)
   - Manages access tokens and API headers
   - Returns `BookingResult` sealed class (Success, AlreadyBooked, Error)
   - Location: `src/main/kotlin/org/dageev/court/CourtAPI.kt:37`

5. **DatabaseFactory** (`org.dageev.database.DatabaseFactory`)
   - Initializes Exposed ORM with PostgreSQL or SQLite
   - Parses PostgreSQL connection URLs (handles `postgresql://` scheme)
   - Creates database schema on startup
   - Location: `src/main/kotlin/org/dageev/database/Database.kt:11`

### Data Model

**Booking** entity (`org.dageev.database.models.Booking`):
- `id` - Auto-increment primary key
- `userId` - Telegram user ID (Long)
- `courtDate` - Court booking date (String, format: YYYY-MM-DD)
- `courtTime` - Court booking time (String, format: HH:MM)
- `createdAt` - Timestamp when booking was created
- `status` - Booking status: "pending", "completed", or "failed"

### Key Workflows

**Scheduling a Booking**:
1. User sends `/schedule 2025-10-25 18:00` to Telegram bot
2. Bot validates date/time format in `TelegramBot.kt:68`
3. Creates new Booking record with status="pending" in `TelegramBot.kt:77`
4. Bot confirms scheduling to user

**Executing Bookings at Midnight**:
1. Scheduler wakes at 23:57 daily in `BookingScheduler.kt:32`
2. Loads all pending bookings for tomorrow in `BookingScheduler.kt:69`
3. Authenticates with CourtAPI at `BookingScheduler.kt:86`
4. Waits until exactly midnight using `waitUntilMidnight()` at `BookingScheduler.kt:83`
5. Processes each booking via `processBooking()` at `BookingScheduler.kt:98`
6. Updates booking status and notifies user in `BookingScheduler.kt:128`

**Database URL Parsing**:
- The bot parses `DATABASE_URL` format: `postgresql://username:password@host:port/database`
- Parsing logic in `DatabaseFactory.parsePostgresUrl()` at `Database.kt:14`
- Credentials are extracted and passed separately to JDBC driver
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
- **Authentication**: CourtAPI caches access token in memory; re-authenticates if token is missing
- **API Headers**: Court booking requires specific headers including `api-token`, `x-custom-identifier`, and `origin` (see `CourtAPI.kt:73-78`)
- **Error Handling**: All booking failures are logged and users are notified via Telegram with error details
