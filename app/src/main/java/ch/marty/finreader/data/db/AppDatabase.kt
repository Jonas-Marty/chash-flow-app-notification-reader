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
        RecurringRuleCache::class,
    ],
    version = 7,
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

        /** Where the payment happened, once location capture exists. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN longitude REAL")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN locationAccuracyM REAL")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN locationAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Rules can now keep their payments off the map. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule ADD COLUMN locationMode TEXT NOT NULL DEFAULT 'ATTACH'")
                db.execSQL("ALTER TABLE rule ADD COLUMN locationExcludePattern TEXT")
                db.execSQL(
                    "ALTER TABLE outbox_item ADD COLUMN attachLocation INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /** A rule can fill in a bill on a recurring entry instead of posting. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule ADD COLUMN target TEXT NOT NULL DEFAULT 'PENDING_TRANSACTION'")
                db.execSQL("ALTER TABLE rule ADD COLUMN recurringRuleId TEXT")
                db.execSQL("ALTER TABLE rule ADD COLUMN recurringRuleName TEXT")
                db.execSQL(
                    "ALTER TABLE outbox_item ADD COLUMN target TEXT NOT NULL DEFAULT 'PENDING_TRANSACTION'",
                )
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN recurringRuleName TEXT")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN recurringDueOn TEXT")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `recurring_rule_cache` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `acceptsProposals` INTEGER NOT NULL, `estimatedAmount` REAL,
                        `nextDueOn` TEXT, `sortIndex` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
                )
            }
        }

        /**
         * The location keeps improving after the payment is sent: the server
         * takes a better fix on a repeat POST while the row is still pending.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN locationSentAccuracyM REAL")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN locationRefineAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE outbox_item ADD COLUMN locationNote TEXT")
            }
        }

        /** A transfer rule's destination account. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rule ADD COLUMN destinationAccountId TEXT")
                db.execSQL("ALTER TABLE rule ADD COLUMN destinationAccountName TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "finreader.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            ).build().also { instance = it }
        }
    }
}
