package com.replydev.reveille.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ScheduleDao {
    @Transaction
    @Query("SELECT * FROM policy ORDER BY name")
    fun observePolicies(): Flow<List<PolicyWithChildren>>

    @Transaction
    @Query("SELECT * FROM policy ORDER BY name")
    suspend fun policies(): List<PolicyWithChildren>

    @Transaction
    @Query("SELECT * FROM policy WHERE id = :id")
    suspend fun policy(id: Long): PolicyWithChildren?

    @Query("SELECT EXISTS(SELECT 1 FROM policy WHERE id = :id)")
    suspend fun policyExists(id: Long): Boolean

    @Query("SELECT * FROM one_off ORDER BY date, time")
    fun observeOneOffs(): Flow<List<OneOffEntity>>

    @Query("SELECT * FROM one_off ORDER BY date, time")
    suspend fun oneOffs(): List<OneOffEntity>

    @Query("SELECT * FROM one_off WHERE id = :id")
    suspend fun oneOff(id: Long): OneOffEntity?

    @Query("SELECT * FROM skip")
    fun observeSkips(): Flow<List<SkipEntity>>

    @Query("SELECT * FROM skip")
    suspend fun skips(): List<SkipEntity>

    @Insert
    suspend fun insertPolicy(policy: PolicyEntity): Long

    @Update
    suspend fun updatePolicy(policy: PolicyEntity)

    @Query("DELETE FROM policy WHERE id = :id")
    suspend fun deletePolicy(id: Long)

    @Query("UPDATE policy SET enabled = :enabled WHERE id = :id")
    suspend fun setPolicyEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM rule WHERE policyId = :policyId")
    suspend fun deleteRules(policyId: Long)

    @Insert
    suspend fun insertRules(rules: List<RuleEntity>)

    @Query("DELETE FROM pause WHERE policyId = :policyId")
    suspend fun deletePauses(policyId: Long)

    @Insert
    suspend fun insertPauses(pauses: List<PauseEntity>)

    /** Inserts or updates [policy] and replaces all its rules and pauses. Returns the policy id. */
    @Transaction
    suspend fun savePolicy(policy: PolicyEntity, rules: List<RuleEntity>, pauses: List<PauseEntity>): Long {
        val id = if (policy.id == 0L) insertPolicy(policy) else policy.id.also { updatePolicy(policy) }
        deleteRules(id)
        insertRules(rules.map { it.copy(id = 0, policyId = id) })
        deletePauses(id)
        insertPauses(pauses.map { it.copy(id = 0, policyId = id) })
        return id
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOneOff(oneOff: OneOffEntity): Long

    /** Returns the number of deleted rows. */
    @Query("DELETE FROM one_off WHERE id = :id")
    suspend fun deleteOneOff(id: Long): Int

    /** Returns -1 when the skip already exists. The policy must exist (foreign key). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkip(skip: SkipEntity): Long

    @Query("DELETE FROM skip WHERE policyId = :policyId AND date = :date")
    suspend fun deleteSkip(policyId: Long, date: LocalDate)

    @Query("DELETE FROM one_off WHERE date < :before")
    suspend fun deleteOneOffsBefore(before: LocalDate)

    @Query("DELETE FROM skip WHERE date < :before")
    suspend fun deleteSkipsBefore(before: LocalDate)
}
