package org.dageev.database.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object Bookings : IntIdTable() {
    val userId = long("user_id")
    val courtDate = varchar("court_date", 10) // YYYY-MM-DD
    val courtTime = varchar("court_time", 5)  // HH:MM
    val courtNumber = integer("court_number").default(4) // 3 or 4
    val createdAt = datetime("created_at")
    val status = varchar("status", 20).default("pending") // pending, completed, failed
}

class Booking(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Booking>(Bookings)

    var userId by Bookings.userId
    var courtDate by Bookings.courtDate
    var courtTime by Bookings.courtTime
    var courtNumber by Bookings.courtNumber
    var createdAt by Bookings.createdAt
    var status by Bookings.status
}