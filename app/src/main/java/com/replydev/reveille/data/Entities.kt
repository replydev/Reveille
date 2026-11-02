package com.replydev.reveille.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.replydev.reveille.engine.OneOffMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "policy")
data class PolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
)

@Entity(
    tableName = "rule",
    foreignKeys = [ForeignKey(entity = PolicyEntity::class, parentColumns = ["id"], childColumns = ["policyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("policyId")],
)
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val policyId: Long,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val intervalWeeks: Int = 1,
    val anchorDate: LocalDate,
    val label: String? = null,
)

@Entity(
    tableName = "pause",
    foreignKeys = [ForeignKey(entity = PolicyEntity::class, parentColumns = ["id"], childColumns = ["policyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("policyId")],
)
data class PauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val policyId: Long,
    val fromDate: LocalDate,
    val toDate: LocalDate,
)

@Entity(tableName = "one_off")
data class OneOffEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val time: LocalTime,
    val label: String? = null,
    val mode: OneOffMode = OneOffMode.REPLACE_DAY,
)

@Entity(
    tableName = "skip",
    primaryKeys = ["policyId", "date"],
    foreignKeys = [ForeignKey(entity = PolicyEntity::class, parentColumns = ["id"], childColumns = ["policyId"], onDelete = ForeignKey.CASCADE)],
)
data class SkipEntity(val policyId: Long, val date: LocalDate)

/**
 * FIRED = claimed and ringing (or retried by the ring guard) but not yet handled; every other value is terminal.
 * SUPERSEDED = a newer alarm started ringing while this one was still unhandled.
 */
enum class FireOutcome { FIRED, DISMISSED, SNOOZED, AUTO_SNOOZED, SKIPPED, MISSED, SUPERSEDED }

@Entity(tableName = "fire_log", indices = [Index(value = ["occurrenceKey", "scheduledAt"], unique = true)])
data class FireLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurrenceKey: String,
    val label: String,
    val scheduledAt: Long,
    val firedAt: Long?,
    val outcome: FireOutcome,
)

data class HandledOccurrence(val occurrenceKey: String, val scheduledAt: Long)

data class PolicyWithChildren(
    @Embedded val policy: PolicyEntity,
    @Relation(parentColumn = "id", entityColumn = "policyId") val rules: List<RuleEntity>,
    @Relation(parentColumn = "id", entityColumn = "policyId") val pauses: List<PauseEntity>,
)
