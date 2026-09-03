package ch.marty.finreader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MonitoredApp::class,
        CapturedNotification::class,
        Rule::class,
        OutboxItem::class,
        AccountCache::class,
        CategoryCache::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun monitoredApps(): MonitoredAppDao
    abstract fun captured(): CapturedDao
    abstract fun rules(): RuleDao
    abstract fun outbox(): OutboxDao
    abstract fun cache(): CacheDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "finreader.db",
            ).build().also { instance = it }
        }
    }
}
