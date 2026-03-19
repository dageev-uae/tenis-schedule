# AGENTS.md

This file provides working guidance for coding agents in this repository.

## Project Summary

- Kotlin application for a Telegram bot that books tennis courts.
- Booking behavior is time-sensitive and uses the `Asia/Dubai` timezone.
- Local development uses SQLite by default; production uses PostgreSQL via `DATABASE_URL`.

## Repo Layout

- `src/main/kotlin/Main.kt` - application entry point and wiring.
- `src/main/kotlin/org/dageev/bot/TelegramBot.kt` - Telegram command handling and immediate booking flow.
- `src/main/kotlin/org/dageev/scheduler/BookingScheduler.kt` - scheduled booking execution around midnight Dubai time.
- `src/main/kotlin/org/dageev/court/CourtAPI.kt` - authentication and booking API integration.
- `src/main/kotlin/org/dageev/database/Database.kt` - database initialization and connection selection.
- `src/main/kotlin/org/dageev/database/models/` - Exposed table and entity definitions.
- `src/test/kotlin/` - tests.

## Common Commands

```bash
./gradlew build
./gradlew test
./gradlew run
./gradlew shadowJar
./gradlew clean build
```

## Environment

Expected environment variables:

- `TELEGRAM_BOT_TOKEN`
- `COURT_USERNAME`
- `COURT_PASSWORD`
- `DATABASE_URL` for PostgreSQL deployments
- `DATABASE_PATH` for local SQLite runs

Do not commit secrets or real credential values.

## Implementation Notes

- Preserve Dubai timezone behavior unless the user explicitly asks to change it.
- The current design distinguishes between immediate bookings and scheduled bookings:
  - dates within 2 days are handled immediately
  - dates more than 2 days away are persisted for the scheduler
- Keep booking logic changes consistent across `TelegramBot` and `BookingScheduler`.
- Be careful with changes to API headers, auth parsing, and fixed booking parameters in `CourtAPI`.
- Keep database changes compatible with both SQLite and PostgreSQL unless the task explicitly narrows scope.

## Editing Guidance

- Make focused changes; avoid refactoring unrelated areas.
- Follow existing Kotlin style and keep code straightforward.
- Prefer updating adjacent documentation when behavior changes.
- If you add files that generate output or local state, keep `.gitignore` current.

## Validation

- Prefer targeted verification first, then broader checks if needed.
- Use `./gradlew test` for behavior changes.
- Use `./gradlew build` when changing wiring, dependencies, or packaging behavior.

