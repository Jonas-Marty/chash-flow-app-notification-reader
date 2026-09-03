package ch.marty.finreader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitoredAppDao {
    @Query("SELECT * FROM monitored_app ORDER BY appLabel COLLATE NOCASE")
    fun observeAll(): Flow<List<MonitoredApp>>

    @Query("SELECT * FROM monitored_app WHERE enabled = 1")
    suspend fun enabled(): List<MonitoredApp>

    @Query("SELECT * FROM monitored_app WHERE packageName = :pkg")
    suspend fun byPackage(pkg: String): MonitoredApp?

    @Upsert
    suspend fun upsert(app: MonitoredApp)

    @Query("DELETE FROM monitored_app WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}

@Dao
interface CapturedDao {
    @Query("SELECT * FROM captured_notification ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notification WHERE matchState = :state ORDER BY postedAt DESC LIMIT :limit")
    fun observeByState(state: MatchState, limit: Int = 300): Flow<List<CapturedNotification>>

    @Query("SELECT * FROM captured_notification WHERE packageName = :pkg ORDER BY postedAt DESC LIMIT :limit")
    suspend fun recentForPackage(pkg: String, limit: Int = 50): List<CapturedNotification>

    @Query("SELECT * FROM captured_notification WHERE id = :id")
    suspend fun byId(id: Long): CapturedNotification?

    @Insert
    suspend fun insert(item: CapturedNotification): Long

    @Query(
        """UPDATE captured_notification
           SET matchState = :state, matchedRuleId = :ruleId, outboxId = :outboxId, detail = :detail
           WHERE id = :id""",
    )
    suspend fun updateOutcome(
        id: Long,
        state: MatchState,
        ruleId: String?,
        outboxId: Long?,
        detail: String?,
    )

    @Query("DELETE FROM captured_notification WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM captured_notification WHERE postedAt < :cutoff AND matchState != 'MATCHED'")
    suspend fun purgeOlderThan(cutoff: Long): Int

    @Query(
        """DELETE FROM captured_notification WHERE id NOT IN
           (SELECT id FROM captured_notification ORDER BY postedAt DESC LIMIT :keep)""",
    )
    suspend fun trimTo(keep: Int): Int

    @Query("SELECT COUNT(*) FROM captured_notification WHERE matchState = :state")
    fun countByState(state: MatchState): Flow<Int>
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rule ORDER BY packageName, priority, name COLLATE NOCASE")
    fun observeAll(): Flow<List<Rule>>

    @Query("SELECT * FROM rule WHERE packageName = :pkg AND enabled = 1 ORDER BY priority, name COLLATE NOCASE")
    suspend fun enabledForPackage(pkg: String): List<Rule>

    @Query("SELECT * FROM rule WHERE id = :id")
    suspend fun byId(id: String): Rule?

    @Query("SELECT * FROM rule")
    suspend fun all(): List<Rule>

    @Upsert
    suspend fun upsert(rule: Rule)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<Rule>)

    @Query("DELETE FROM rule WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_item WHERE id = :id")
    suspend fun byId(id: Long): OutboxItem?

    @Query("SELECT * FROM outbox_item ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<OutboxItem>>

    @Query(
        """SELECT * FROM outbox_item
           WHERE state IN ('QUEUED', 'FAILED_RETRY') AND notBefore <= :now
           ORDER BY createdAt LIMIT :limit""",
    )
    suspend fun dueForSending(now: Long, limit: Int = 50): List<OutboxItem>

    @Query("SELECT MIN(notBefore) FROM outbox_item WHERE state IN ('QUEUED', 'FAILED_RETRY') AND notBefore > :now")
    suspend fun nextDueAfter(now: Long): Long?

    @Query("SELECT COUNT(*) FROM outbox_item WHERE state IN ('QUEUED', 'FAILED_RETRY', 'SENDING', 'HELD')")
    fun countUnsent(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_item WHERE state = 'FAILED_PERMANENT'")
    fun countFailed(): Flow<Int>

    /**
     * Items sharing a base key, used both for duplicate detection and for
     * deriving the repeat counter of a genuinely repeated payment.
     */
    @Query(
        """SELECT * FROM outbox_item
           WHERE externalRefBase = :base AND state != 'CANCELLED'
           ORDER BY createdAt""",
    )
    suspend fun siblingsOf(base: String): List<OutboxItem>

    @Insert
    suspend fun insert(item: OutboxItem): Long

    @Query(
        """UPDATE outbox_item
           SET state = :state, attempts = :attempts, lastError = :lastError,
               remotePendingId = :remoteId, updatedAt = :now
           WHERE id = :id""",
    )
    suspend fun updateResult(
        id: Long,
        state: OutboxState,
        attempts: Int,
        lastError: String?,
        remoteId: String?,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE outbox_item SET state = :state, notBefore = :notBefore, updatedAt = :now WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: OutboxState,
        notBefore: Long = 0,
        now: Long = System.currentTimeMillis(),
    )

    /**
     * Everything that reached the server and could still change there.
     *
     * CONFIRMED and GONE are dropped: the web app never un-confirms a
     * transaction, and a deleted row cannot come back under the same ref.
     * REJECTED stays in — "restore" puts it back on the pending list.
     */
    @Query(
        """SELECT * FROM outbox_item
           WHERE state IN ('POSTED', 'DEDUPED')
             AND (serverStatus IS NULL OR serverStatus NOT IN ('CONFIRMED', 'GONE'))
           ORDER BY createdAt DESC LIMIT :limit""",
    )
    suspend fun postedItems(limit: Int = 200): List<OutboxItem>

    @Query(
        """UPDATE outbox_item
           SET serverStatus = :status, confirmedTransactionId = :transactionId,
               rejectReason = :rejectReason, remotePendingId = COALESCE(:remoteId, remotePendingId),
               statusCheckedAt = :now, updatedAt = :now
           WHERE id = :id""",
    )
    suspend fun updateServerStatus(
        id: Long,
        status: ServerStatus?,
        transactionId: String?,
        rejectReason: String?,
        remoteId: String?,
        now: Long = System.currentTimeMillis(),
    )

    /** Used by a re-run, once the row it refers to is gone from the server. */
    @Query("DELETE FROM outbox_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Recovers items left mid-flight by a killed worker. */
    @Query("UPDATE outbox_item SET state = 'QUEUED' WHERE state = 'SENDING' AND updatedAt < :cutoff")
    suspend fun recoverStaleSending(cutoff: Long): Int

    @Query("DELETE FROM outbox_item WHERE state IN ('POSTED', 'DEDUPED', 'CANCELLED') AND createdAt < :cutoff")
    suspend fun purgeOlderThan(cutoff: Long): Int
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM account_cache WHERE archived = 0 ORDER BY sortIndex, name COLLATE NOCASE")
    fun observeAccounts(): Flow<List<AccountCache>>

    @Query("SELECT * FROM category_cache WHERE archived = 0 ORDER BY sortIndex, name COLLATE NOCASE")
    fun observeCategories(): Flow<List<CategoryCache>>

    @Query("SELECT * FROM account_cache")
    suspend fun accounts(): List<AccountCache>

    @Transaction
    suspend fun replaceAccounts(items: List<AccountCache>) {
        clearAccounts()
        insertAccounts(items)
    }

    @Transaction
    suspend fun replaceCategories(items: List<CategoryCache>) {
        clearCategories()
        insertCategories(items)
    }

    @Query("DELETE FROM account_cache")
    suspend fun clearAccounts()

    @Query("DELETE FROM category_cache")
    suspend fun clearCategories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccounts(items: List<AccountCache>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<CategoryCache>)
}
