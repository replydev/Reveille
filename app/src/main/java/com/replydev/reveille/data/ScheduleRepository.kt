package com.replydev.reveille.data

import com.replydev.reveille.engine.ScheduleSnapshot
import com.replydev.reveille.engine.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
        dao.savePolicy(policy, rules, pauses).also { onChanged() }

    suspend fun deletePolicy(id: Long) {
        dao.deletePolicy(id)
        onChanged()
    }

    suspend fun setPolicyEnabled(id: Long, enabled: Boolean) {
        dao.setPolicyEnabled(id, enabled)
        onChanged()
    }

    suspend fun saveOneOff(oneOff: OneOffEntity): Long = dao.saveOneOff(oneOff).also { onChanged() }

    suspend fun deleteOneOff(id: Long) {
        dao.deleteOneOff(id)
        onChanged()
    }

    suspend fun unskip(policyId: Long, date: LocalDate) {
        dao.deleteSkip(policyId, date)
        onChanged()
    }

    /**
     * Skips policy occurrences and deletes one-offs referenced by [sourceKeys]. Sources deleted in the meantime are
     * ignored; [onChanged] runs only if something changed.
     */
    suspend fun skipOccurrence(sourceKeys: List<String>) {
        var changed = false
        sourceKeys.mapNotNull(Source::parse).forEach { source ->
            when (source) {
                is Source.PolicyRule ->
                    if (dao.policyExists(source.policyId)) changed = dao.insertSkip(SkipEntity(source.policyId, source.date)) != -1L || changed
                is Source.OneOffAlarm -> changed = dao.deleteOneOff(source.oneOffId) > 0 || changed
            }
        }
        if (changed) onChanged()
    }

    /** Deletes one-offs and skips dated before [today]. Does not call [onChanged]: it runs inside a re-arm. */
    suspend fun cleanup(today: LocalDate) {
        dao.deleteOneOffsBefore(today)
        dao.deleteSkipsBefore(today)
    }
}
