/*
 * EventQueueDatabase.kt
 * PYRXSynapse — Android
 *
 * Room database that hosts the offline event queue. Single-table today —
 * future PRs that need additional on-device persistence (e.g., the in-app
 * message inbox in Phase 9) will land in a separate database, not this one,
 * so the schema stays focused on the event-queue use case.
 *
 * Database file:
 *   <app internal>/databases/pyrx_event_queue.db
 *
 * Versioning:
 *   v1 — initial schema (PR 3). Any subsequent schema change MUST ship with
 *        a Migration block here so existing user installs don't lose queued
 *        events on upgrade. Until then `fallbackToDestructiveMigration` is
 *        explicitly NOT enabled — a missing migration is a CI build failure,
 *        not a silent data wipe on the user's device.
 */

package tech.pyrx.synapse.queue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QueuedEventEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class EventQueueDatabase : RoomDatabase() {
    abstract fun queuedEventDao(): QueuedEventDao

    companion object {
        /** Persistent on-disk filename. Stable — see file-header doc. */
        private const val DATABASE_NAME: String = "pyrx_event_queue.db"

        /**
         * Build the production on-disk database. Called once from
         * [tech.pyrx.synapse.Pyrx.initialize].
         */
        fun create(context: Context): EventQueueDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                EventQueueDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
