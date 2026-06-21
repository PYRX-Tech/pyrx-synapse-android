/*
 * QueuedEventDao.kt
 * PYRXSynapse — Android
 *
 * Room DAO for the offline event queue. Every method is `suspend` so the
 * [EventQueue] can call into it from coroutine context without blocking the
 * caller.
 *
 * Ordering contract: every read is `ORDER BY created_at ASC, id ASC` so the
 * queue is strict FIFO regardless of how SQLite chooses to lay out rows. The
 * secondary `id ASC` tie-break only matters if two rows share an identical
 * `created_at` (clock skew under load) — without it, FIFO would be
 * non-deterministic across test runs.
 *
 * Mirrors the iOS JSONL file's at-load behavior — `[QueuedEvent]` ordered
 * oldest-first.
 */

package tech.pyrx.synapse.queue

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface QueuedEventDao {
    /**
     * Insert one row. Conflict strategy is [OnConflictStrategy.REPLACE] —
     * if a row already exists with the same UUID (extremely unlikely
     * outside of test fixtures that re-use a seed), the new copy wins.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QueuedEventEntity)

    /**
     * Update an existing row in place. Used by the drain loop to bump
     * [QueuedEventEntity.attemptCount] on retryable failures without
     * reinserting (which would corrupt `created_at` and break FIFO order).
     */
    @Update
    suspend fun update(entity: QueuedEventEntity)

    /** Delete one row by primary key. */
    @Delete
    suspend fun delete(entity: QueuedEventEntity)

    /**
     * Delete all rows by primary key in a single round-trip. Used when the
     * queue evicts the oldest N rows on overflow (avoids N round-trips).
     */
    @Query("DELETE FROM queued_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Total event count on disk. */
    @Query("SELECT COUNT(*) FROM queued_events")
    suspend fun count(): Int

    /** FIFO read — every event, oldest first. */
    @Query("SELECT * FROM queued_events ORDER BY created_at ASC, id ASC")
    suspend fun selectAll(): List<QueuedEventEntity>

    /**
     * FIFO read — oldest [limit] events. Used by the drain loop so it can
     * pull a bounded batch per pass without holding all events in memory.
     */
    @Query("SELECT * FROM queued_events ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun selectOldest(limit: Int): List<QueuedEventEntity>

    /**
     * Return the IDs of the oldest [limit] rows. Used by the bound-enforcement
     * path so we can [deleteByIds] in one round-trip without materialising
     * full entities.
     */
    @Query("SELECT id FROM queued_events ORDER BY created_at ASC, id ASC LIMIT :limit")
    suspend fun selectOldestIds(limit: Int): List<String>
}
