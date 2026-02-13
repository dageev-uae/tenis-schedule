package org.dageev

import kotlinx.coroutines.runBlocking
import org.dageev.court.CourtAPI
import org.dageev.database.DatabaseFactory
import org.dageev.database.models.AmenitySlot
import org.dageev.database.models.AmenitySlots
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlotFetchAndBookTest {

    private lateinit var courtAPI: CourtAPI

    @BeforeAll
    fun setup() {
        // .env загружается через build.gradle.kts -> tasks.test { environment(...) }
        // Для IDE: запускай через ./gradlew test, или добавь env vars в Run Configuration
        println("COURT_USERNAME = ${System.getenv("COURT_USERNAME")?.take(3)}...")
        DatabaseFactory.init()
        courtAPI = CourtAPI()
    }

    @Test
    fun `fetch slots for Feb 15 and book`() = runBlocking {
        val targetDate = "2026-02-15"

        // 1. Авторизуемся
        val authOk = courtAPI.authenticate()
        println("Auth result: $authOk")
        assert(authOk) { "Authentication failed — check COURT_USERNAME / COURT_PASSWORD env vars" }

        // 2. Fetch слотов для обоих кортов
        for ((courtNumber, amenityId) in courtAPI.courtMapping) {
            val slots = courtAPI.fetchSlots(targetDate, amenityId)
            println("Court $courtNumber: ${slots.size} slots")
            slots.forEach { slot ->
                println("  ${slot.start_time} - ${slot.end_time} (id: ${slot.id})")
            }

            // Сохраняем в БД
            transaction {
                for (slot in slots) {
                    val startTime = normalizeTime(slot.start_time)
                    val endTime = normalizeTime(slot.end_time)

                    val existing = AmenitySlot.findById(slot.id)
                    if (existing == null) {
                        AmenitySlot.new(slot.id) {
                            this.amenityId = amenityId
                            this.startTime = startTime
                            this.endTime = endTime
                        }
                    }
                }
            }
        }

        // 3. Проверяем что слоты в БД
        val allSlots = transaction { AmenitySlot.all().toList() }
        println("\nAll slots in DB: ${allSlots.size}")
        allSlots.forEach { slot ->
            println("  [${slot.amenityId}] ${slot.startTime}-${slot.endTime} (id: ${slot.id.value})")
        }
        assert(allSlots.isNotEmpty()) { "No slots saved to DB" }

        // 4. Бронируем — выбери время и корт
        val bookTime = "06:00"    // <-- поменяй на нужное время
        val bookCourt = 4         // <-- поменяй на нужный корт (3 или 4)

        val amenityId = courtAPI.courtMapping[bookCourt]!!
        val slotId = transaction {
            AmenitySlot.find {
                (AmenitySlots.amenityId eq amenityId) and (AmenitySlots.startTime eq bookTime)
            }.firstOrNull()?.id?.value
        }

        println("\nBooking: date=$targetDate, time=$bookTime, court=$bookCourt, slotId=$slotId, amenityId=$amenityId")
        assert(slotId != null) { "Slot not found for time=$bookTime, court=$bookCourt" }

        val result = courtAPI.bookCourt(targetDate, slotId!!, amenityId)
        println("Booking result: $result")
    }

    private fun normalizeTime(time: String): String {
        val parts = time.split(":")
        if (parts.size >= 2) {
            val hour = parts[0].trim().padStart(2, '0')
            val minute = parts[1].trim().padStart(2, '0')
            return "$hour:$minute"
        }
        return time
    }
}
