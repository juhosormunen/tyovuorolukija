package fi.tyovuorolukija.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Kirjanpito siitä mikä vuoro vastaa mitäkin kalenteritapahtumaa.
 *
 * Tarkoitus on idempotenssi: sama jakso skannataan uudestaan kun vuorot muuttuvat.
 * Ilman tätä uudelleenskannaus tuottaisi kaksoiskappaleita. Tapahtuman omaan
 * SYNC_DATA-kenttään ei voi kirjoittaa ilman sync adapter -oikeuksia, joten
 * mäppäys pidetään paikallisesti.
 *
 * Avain on (kalenteri, päivä, alkuaika) — sama päivä voi teoriassa sisältää
 * kaksi vuoroa, mutta ei kahta samaan kellonaikaan alkavaa.
 */
@Entity(
    tableName = "synced_shifts",
    indices = [Index(value = ["calendarId", "localDate", "startMillis"], unique = true)],
)
data class SyncedShift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val calendarId: Long,
    /** ISO-päivä (vuoron alkupäivä) — helpottaa jaksovälin kyselyä. */
    val localDate: String,
    val startMillis: Long,
    val endMillis: Long,
    val code: String?,
    val title: String,
    val eventId: Long,
)

/**
 * Yksi tallennuskerta. Undo kumoaa aina viimeisimmän erän kokonaisuutena —
 * puolittainen kumoaminen jättäisi kalenterin epämääräiseen välitilaan.
 */
@Entity(tableName = "sync_batches")
data class SyncBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val calendarId: Long,
    val calendarName: String,
    val createdAt: Long,
)

/**
 * Yksi kalenteriin tehty muutos ja tieto siitä, mikä tila sitä edelsi.
 *
 * Prev-kentät ovat se, mitä kalenterissa oikeasti luki ennen muutosta — ei se mitä
 * me viimeksi kirjoitimme. Käyttäjä on voinut muokata tapahtumaa kalenterisovelluksessa,
 * ja undon pitää palauttaa hänen versionsa, ei meidän.
 */
@Entity(tableName = "sync_actions", indices = [Index("batchId")])
data class SyncAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    /** INSERT | UPDATE | DELETE */
    val action: String,
    val eventId: Long,
    val calendarId: Long,
    val localDate: String,
    val startMillis: Long,
    val endMillis: Long,
    val code: String?,
    val title: String,
    val prevTitle: String? = null,
    val prevStartMillis: Long? = null,
    val prevEndMillis: Long? = null,
    val prevDescription: String? = null,
) {
    companion object {
        const val INSERT = "INSERT"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
    }
}

/**
 * Yksi tallennettu jakso historiassa.
 *
 * Tallennetaan vasta kun käyttäjä on hyväksynyt vuorot ja kirjoittanut ne kalenteriin —
 * historia kuvaa siis vahvistettuja jaksoja, ei jokaista skannausyritystä.
 * Rahasummat sentteinä, koska Roomiin ei kannata viedä BigDecimalia.
 *
 * Kytketty [SyncBatch]iin, jotta kumoaminen poistaa myös historiarivin.
 */
@Entity(
    tableName = "scanned_periods",
    indices = [
        Index(value = ["rangeStart", "rangeEnd"], unique = true),
        Index(value = ["batchId"]),
    ],
)
data class ScannedPeriod(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long,
    val scannedAt: Long,
    val rangeStart: String,
    val rangeEnd: String,

    val totalMinutes: Long,
    val eveningMinutes: Long,
    val nightMinutes: Long,
    val saturdayMinutes: Long,
    val sundayMinutes: Long,

    /** Työnantajan ilmoittamat kokonaistunnit ja täsmäsikö oma laskelma. */
    val employerTotalMinutes: Long?,
    val employerMatched: Boolean?,

    val shiftCount: Int,
    val nightShiftCount: Int,
    val weekendShiftCount: Int,
    val longestWorkStreakDays: Int,
    val shortestRestMinutes: Long?,
    val shortRestCount: Int,
    val freeDayCount: Int,

    val monthlySalaryCents: Long?,
    val supplementsCents: Long?,
    val grossCents: Long?,
    val netCents: Long?,
)

@Dao
interface ScannedPeriodDao {

    @Query("SELECT * FROM scanned_periods ORDER BY rangeStart")
    suspend fun all(): List<ScannedPeriod>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(period: ScannedPeriod)

    @Query("DELETE FROM scanned_periods WHERE batchId = :batchId")
    suspend fun deleteByBatch(batchId: Long)

    @Query("SELECT COUNT(*) FROM scanned_periods")
    suspend fun count(): Int
}

@Dao
interface SyncedShiftDao {

    @Query("SELECT * FROM synced_shifts WHERE calendarId = :calendarId AND localDate BETWEEN :from AND :to")
    suspend fun inRange(calendarId: Long, from: String, to: String): List<SyncedShift>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(shift: SyncedShift)

    @Query("DELETE FROM synced_shifts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM synced_shifts WHERE eventId = :eventId")
    suspend fun deleteByEventId(eventId: Long)

    @Query("SELECT * FROM synced_shifts WHERE eventId = :eventId LIMIT 1")
    suspend fun byEventId(eventId: Long): SyncedShift?
}

@Dao
interface SyncBatchDao {

    @Insert
    suspend fun insertBatch(batch: SyncBatch): Long

    @Insert
    suspend fun insertAction(action: SyncAction)

    @Query("SELECT * FROM sync_batches ORDER BY id DESC LIMIT 1")
    suspend fun lastBatch(): SyncBatch?

    @Query("SELECT * FROM sync_actions WHERE batchId = :batchId ORDER BY id DESC")
    suspend fun actionsFor(batchId: Long): List<SyncAction>

    @Query("SELECT COUNT(*) FROM sync_actions WHERE batchId = :batchId")
    suspend fun actionCount(batchId: Long): Int

    @Query("DELETE FROM sync_actions WHERE batchId = :batchId")
    suspend fun deleteActions(batchId: Long)

    @Query("DELETE FROM sync_batches WHERE id = :batchId")
    suspend fun deleteBatch(batchId: Long)

    /** Undo koskee vain viimeisintä erää, joten vanhat voi siivota pois. */
    @Query("DELETE FROM sync_batches WHERE id < :keepFromId")
    suspend fun pruneBatchesBefore(keepFromId: Long)

    @Query("DELETE FROM sync_actions WHERE batchId < :keepFromId")
    suspend fun pruneActionsBefore(keepFromId: Long)
}

@Database(
    entities = [SyncedShift::class, SyncBatch::class, SyncAction::class, ScannedPeriod::class],
    version = 3,
    exportSchema = false,
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun syncedShifts(): SyncedShiftDao
    abstract fun syncBatches(): SyncBatchDao
    abstract fun scannedPeriods(): ScannedPeriodDao

    companion object {
        /**
         * v1 -> v2: undo-journaali. Lisää vain uusia tauluja, joten aiemmin skannattujen
         * jaksojen mäppäys (synced_shifts) säilyy eikä idempotenssi rikkoudu.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_batches` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`calendarId` INTEGER NOT NULL, " +
                        "`calendarName` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_actions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`batchId` INTEGER NOT NULL, " +
                        "`action` TEXT NOT NULL, " +
                        "`eventId` INTEGER NOT NULL, " +
                        "`calendarId` INTEGER NOT NULL, " +
                        "`localDate` TEXT NOT NULL, " +
                        "`startMillis` INTEGER NOT NULL, " +
                        "`endMillis` INTEGER NOT NULL, " +
                        "`code` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`prevTitle` TEXT, " +
                        "`prevStartMillis` INTEGER, " +
                        "`prevEndMillis` INTEGER, " +
                        "`prevDescription` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_actions_batchId` " +
                        "ON `sync_actions` (`batchId`)"
                )
            }
        }

        /** v2 -> v3: jaksohistoria. Lisää vain uuden taulun. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scanned_periods` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`batchId` INTEGER NOT NULL, " +
                        "`scannedAt` INTEGER NOT NULL, " +
                        "`rangeStart` TEXT NOT NULL, " +
                        "`rangeEnd` TEXT NOT NULL, " +
                        "`totalMinutes` INTEGER NOT NULL, " +
                        "`eveningMinutes` INTEGER NOT NULL, " +
                        "`nightMinutes` INTEGER NOT NULL, " +
                        "`saturdayMinutes` INTEGER NOT NULL, " +
                        "`sundayMinutes` INTEGER NOT NULL, " +
                        "`employerTotalMinutes` INTEGER, " +
                        "`employerMatched` INTEGER, " +
                        "`shiftCount` INTEGER NOT NULL, " +
                        "`nightShiftCount` INTEGER NOT NULL, " +
                        "`weekendShiftCount` INTEGER NOT NULL, " +
                        "`longestWorkStreakDays` INTEGER NOT NULL, " +
                        "`shortestRestMinutes` INTEGER, " +
                        "`shortRestCount` INTEGER NOT NULL, " +
                        "`freeDayCount` INTEGER NOT NULL, " +
                        "`monthlySalaryCents` INTEGER, " +
                        "`supplementsCents` INTEGER, " +
                        "`grossCents` INTEGER, " +
                        "`netCents` INTEGER)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_scanned_periods_rangeStart_rangeEnd` " +
                        "ON `scanned_periods` (`rangeStart`, `rangeEnd`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scanned_periods_batchId` " +
                        "ON `scanned_periods` (`batchId`)"
                )
            }
        }

        @Volatile
        private var instance: ShiftDatabase? = null

        fun get(context: Context): ShiftDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ShiftDatabase::class.java,
                "tyovuorolukija.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
