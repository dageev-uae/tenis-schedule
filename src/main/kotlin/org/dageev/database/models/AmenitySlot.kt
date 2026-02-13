package org.dageev.database.models

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable

object AmenitySlots : IdTable<String>("amenity_slots") {
    override val id = varchar("slot_id", 30).entityId()
    val amenityId = varchar("amenity_id", 30)
    val startTime = varchar("start_time", 5) // HH:MM
    val endTime = varchar("end_time", 5)     // HH:MM
    override val primaryKey = PrimaryKey(id)
}

class AmenitySlot(id: EntityID<String>) : Entity<String>(id) {
    companion object : EntityClass<String, AmenitySlot>(AmenitySlots)

    var amenityId by AmenitySlots.amenityId
    var startTime by AmenitySlots.startTime
    var endTime by AmenitySlots.endTime
}