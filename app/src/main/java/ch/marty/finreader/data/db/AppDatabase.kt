package ch.marty.finreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MonitoredApp::class,
        CapturedNotification::class,
        Rule::class,
        OutboxItem::class,
        AccountCache::class,
        CategoryCache::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitoredApps(): MonitoredAppDao
    abstract fun captured(): CapturedDao
    abstract fun rules(): RuleDao
    abstract fun outbox(): OutboxDao
    abstract fun cache(): CacheDao

    companion object {
        /**
         * Adds the web app's verdict to a posted item. Destructive migration is
         * not an option here — the phone holds the only copy of the captures and
         * the rules written against them.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN serverStatus TEXT")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN confirmedTransactionId TEXT")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN rejectReason TEXT")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN statusCheckedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "finreader.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
