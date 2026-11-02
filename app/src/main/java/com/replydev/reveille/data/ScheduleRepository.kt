package com.replydev.reveille.data

import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** All schedule writes go through here; each write calls [onChanged] so the scheduler re-arms. */
class ScheduleRepository(private val dao: ScheduleDao, private val onChanged: suspend () -> Unit) {
    val policies: Flow<List<PolicyWithChildren>> = dao.observePolicies()

    val snapshots: Flow<ScheduleSnapshot> =
        combine(dao.observePolicies(), dao.observeOneOffs(), dao.observeSkips()) { policies, oneOffs, skips ->
            ScheduleSnapshot(policies.map { it.toModel() }, oneOffs.map { it.toModel() }, skips.map { it.toModel() })
        }

    suspend fun snapshot(): ScheduleSnapshot =
        ScheduleSnapshot(dao.policies().map { it.toModel() }, dao.oneOffs().map { it.toModel() }, dao.skips().map { it.toModel() })

    suspend fun policy(id: Long): PolicyWithChildren? = dao.policy(id)

    suspend fun oneOff(id: Long): OneOffEntity? = dao.oneOff(id)

    suspend fun savePolicy(policy: PolicyEntity, rules: List<RuleEntity>, pauses: List<PauseEntity>): Long =
        writeAndRearm { dao.savePolicy(policy, rules, pauses) }

    suspend fun deletePolicy(id: Long) = writeAndRearm { dao.deletePolicy(id) }

    suspend fun setPolicyEnabled(id: Long, enabled: Boolean) = writeAndRearm { dao.setPolicyEnabled(id, enabled) }

    suspend fun saveOneOff(oneOff: OneOffEntity): Long = writeAndRearm { dao.saveOneOff(oneOff) }

    suspend fun deleteOneOff(id: Long) {
        writeAndRearm { dao.deleteOneOff(id) }
    }

    suspend fun unskip(policyId: Long, date: LocalDate) = writeAndRearm { dao.deleteSkip(policyId, date) }

    /**
     * Skips policy occurrences and deletes one-offs referenced by [sourceKeys]. Sources deleted in the meantime are
     * ignored; [onChanged] runs only if something changed and [notify] is set (the scheduler passes false because it
     * calls this while holding its re-arm lock and re-arms itself). Returns whether anything changed.
     *
     * Skipping a policy occurrence skips that policy for its whole date, by design (spec: "Skip cancels the alarm for
     * one policy on one date"): other rules of the same policy on that date do not ring either.
     */
    suspend fun skipOccurrence(sourceKeys: List<String>, notify: Boolean = true): Boolean = withContext(NonCancellable) {
        var changed = false
        sourceKeys.mapNotNull(Source::parse).forEach { source ->
            when (source) {
                is Source.PolicyRule ->
                    if (dao.policyExists(source.policyId)) changed = dao.insertSkip(SkipEntity(source.policyId, source.date)) != -1L || changed
                is Source.OneOffAlarm -> changed = dao.deleteOneOff(source.oneOffId) > 0 || changed
            }
        }
        if (changed && notify) onChanged()
        changed
    }

    /** Deletes one-offs and skips dated before [today]. Does not call [onChanged]: it runs inside a re-arm. */
    suspend fun cleanup(today: LocalDate) {
        dao.deleteOneOffsBefore(today)
        dao.deleteSkipsBefore(today)
    }

    /**
     * Runs [write] and then [onChanged] as one non-cancellable unit. Cancelling the caller (e.g. closing the screen
     * that saved) must never leave a committed write without its re-arm, or AlarmManager keeps the old alarm. This
     * covers cancellation during the Room call too: Room may commit even though the call ends with a cancellation.
     */
    private suspend fun <T> writeAndRearm(write: suspend () -> T): T = withContext(NonCancellable) {
        write().also { onChanged() }
    }
}
