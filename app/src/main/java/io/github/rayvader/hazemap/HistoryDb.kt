package io.github.rayvader.hazemap

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** One stored reading: a region's value for a metric at one NEA timestamp. */
@Entity(
    tableName = "readings",
    // A region+metric+timestamp is unique, so re-fetching the same NEA snapshot does not duplicate it.
    indices = [androidx.room.Index(value = ["region", "metric", "timestampMillis"], unique = true)]
)
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "region") val region: String,
    @ColumnInfo(name = "metric") val metric: String,
    @ColumnInfo(name = "value") val value: Int,
    @ColumnInfo(name = "timestampMillis") val timestampMillis: Long
)

@Dao
interface ReadingDao {
    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<ReadingEntity>)

    @Query("SELECT * FROM readings WHERE region = :region AND metric = :metric ORDER BY timestampMillis ASC")
    fun history(region: String, metric: String): List<ReadingEntity>

    @Query("SELECT COUNT(*) FROM readings")
    fun count(): Int

    @Query("DELETE FROM readings WHERE timestampMillis < :cutoff")
    fun pruneOlderThan(cutoff: Long)
}

@Database(entities = [ReadingEntity::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile private var instance: HistoryDb? = null

        fun get(context: Context): HistoryDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HistoryDb::class.java,
                "haze-history.db"
            ).build().also { instance = it }
        }
    }
}
