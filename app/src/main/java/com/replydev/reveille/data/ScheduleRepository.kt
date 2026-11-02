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
        dao.savePolicy(policy, rules, pauses).also { rearmAfterWrite() }

    suspend fun deletePolicy(id: Long) {
        dao.deletePolicy(id)
        rearmAfterWrite()
    }

    suspend fun setPolicyEnabled(id: Long, enabled: Boolean) {
        dao.setPolicyEnabled(id, enabled)
        rearmAfterWrite()
    }

    suspend fun saveOneOff(oneOff: OneOffEntity): Long = dao.saveOneOff(oneOff).also { rearmAfterWrite() }

    suspend fun deleteOneOff(id: Long) {
        dao.deleteOneOff(id)
        rearmAfterWrite()
    }

    suspend fun unskip(policyId: Long, date: LocalDate) {
        dao.deleteSkip(policyId, date)
        rearmAfterWrite()
    }

    /**
     * Skips policy occurrences and deletes one-offs referenced by [sourceKeys]. Sources deleted in the meantime are
     * ignored; [onChanged] runs only if something changed and [notify] is set (the scheduler passes false because it
     * calls this while holding its re-arm lock and re-arms itself). Returns whether anything changed.
     *
     * Skipping a policy occurrence skips that policy for its whole date, by design (spec: "Skip cancels the alarm for
     * one policy on one date"): other rules of the same policy on that date do not ring either.
     */
    suspend fun skipOccurrence(sourceKeys: List<String>, notify: Boolean = true): Boolean {
        var changed = false
        sourceKeys.mapNotNull(Source::parse).forEach { source ->
            when (source) {
                is Source.PolicyRule ->
                    if (dao.policyExists(source.policyId)) changed = dao.insertSkip(SkipEntity(source.policyId, source.date)) != -1L || changed
                is Source.OneOffAlarm -> changed = dao.deleteOneOff(source.oneOffId) > 0 || changed
            }
        }
        if (changed && notify) rearmAfterWrite()
        return changed
    }

    /** Deletes one-offs and skips dated before [today]. Does not call [onChanged]: it runs inside a re-arm. */
    suspend fun cleanup(today: LocalDate) {
        dao.deleteOneOffsBefore(today)
        dao.deleteSkipsBefore(today)
    }

    /**
     * Runs [onChanged] even if the caller is cancelled (e.g. the screen that saved was closed): once a write is
     * committed, AlarmManager must be updated, or it would keep ringing at the old time.
     */
    private suspend fun rearmAfterWrite() = withContext(NonCancellable) { onChanged() }
}
