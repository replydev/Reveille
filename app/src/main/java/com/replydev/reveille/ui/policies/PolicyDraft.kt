package com.replydev.reveille.ui.policies

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.replydev.reveille.data.PauseEntity
import com.replydev.reveille.data.RuleEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** A rule being edited. [key] identifies the row in the list (a new rule has id 0 until saved). */
data class RuleDraft(val key: Long, val rule: RuleEntity)

data class PauseDraft(val key: Long, val pause: PauseEntity)

fun newDraftKey(): Long = Random.nextLong()

fun emptyRuleDrafts(): SnapshotStateList<RuleDraft> = mutableStateListOf()

fun emptyPauseDrafts(): SnapshotStateList<PauseDraft> = mutableStateListOf()

/** Saves rule drafts as lists of Bundle-friendly values, so the editor survives recreation and process death. */
val RuleDraftsSaver: Saver<SnapshotStateList<RuleDraft>, Any> = listSaver(
    save = { drafts ->
        drafts.map { (key, rule) ->
            arrayListOf<Any?>(
                key, rule.id, rule.policyId, rule.time, rule.days.map { it.value }.toIntArray(),
                rule.intervalWeeks, rule.anchorDate, rule.label,
            )
        }
    },
    restore = { saved ->
        saved.map { values ->
            RuleDraft(
                key = values[0] as Long,
                rule = RuleEntity(
                    id = values[1] as Long,
                    policyId = values[2] as Long,
                    time = values[3] as LocalTime,
                    days = (values[4] as IntArray).map(DayOfWeek::of).toSet(),
                    intervalWeeks = values[5] as Int,
                    anchorDate = values[6] as LocalDate,
                    label = values[7] as String?,
                ),
            )
        }.toMutableStateList()
    },
)

val PauseDraftsSaver: Saver<SnapshotStateList<PauseDraft>, Any> = listSaver(
    save = { drafts -> drafts.map { (key, pause) -> arrayListOf<Any?>(key, pause.id, pause.policyId, pause.fromDate, pause.toDate) } },
    restore = { saved ->
        saved.map { values ->
            PauseDraft(
                key = values[0] as Long,
                pause = PauseEntity(
                    id = values[1] as Long,
                    policyId = values[2] as Long,
                    fromDate = values[3] as LocalDate,
                    toDate = values[4] as LocalDate,
                ),
            )
        }.toMutableStateList()
    },
)
