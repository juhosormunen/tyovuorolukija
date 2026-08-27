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
    val taxCents: Long? = null,
    val contributionsCents: Long? = null,
) {
    /** Vakaa avain jaksolle. Rivin id vaihtuu kun sama jakso skannataan uudestaan. */
    val key: String get() = "$rangeStart..$rangeEnd"
}

/**
 * Yksi päivä jaksossa. Tarpeen kalenterinäkymää varten — jakson yhteenvetoluvuista
 * ei voi piirtää päiväkohtaista ruudukkoa.
 *
 * Kytketty jaksoon [periodKey]:llä eikä id:llä, koska jakson rivi korvataan
 * kokonaan kun sama jakso skannataan uudestaan ja id vaihtuu silloin.
 */
@Entity(
    tableName = "scanned_days",
    indices = [Index(value = ["periodKey", "date"], unique = true)],
)
data class ScannedDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val periodKey: String,
    val date: String,
    val code: String?,
    val isFree: Boolean,
    val minutes: Long,
    /**
     * Vuoron todellinen alku ja loppu. Ilman näitä jakson työaikakorvauksia ei voi
     * laskea uudestaan sen jälkeen kun päivä on merkitty poissaoloksi — pelkistä
     * minuuteista ei näe osuiko vuoro yöhön vai sunnuntaille.
     *
     * Null vapaapäivillä ja ennen skeemaversiota 5 tallennetuilla riveillä, joille
     * ajat ei löytynyt `synced_shifts`-taulusta migraatiossa.
     */
    val startMillis: Long? = null,
    val endMillis: Long? = null,
)

/**
 * Päivä jolta työvuoro jäi tekemättä: sairausloma tai vuosiloma.
 *
 * **Erillinen taulu eikä [ScannedDay]n kenttä**, koska poissaolo ei aina osu
 * skannattuun vuoroon. Lomalle ei suunnitella vuoroja lainkaan, joten lomapäivä ei
 * ole missään jaksossa — ja sairausloman saa tietää vasta jälkikäteen, kun jakso on
 * jo skannattu ja tallennettu. Merkintä on siis päivätason kerros jaksojen päällä.
 *
 * [prevTitle] ja [createdEventId] tekevät merkinnän kumottavaksi: joko tapahtuman
 * otsikko kirjoitettiin päälle (silloin alkuperäinen on talteen otettu) tai
 * tapahtuma luotiin tyhjään päivään (silloin se poistetaan merkintää purettaessa).
 */
@Entity(tableName = "absence_days", indices = [Index(value = ["date"], unique = true)])
data class AbsenceDay(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    /** [fi.tyovuorolukija.data.DayType]-nimi: SICK tai VACATION. */
    val type: String,
    val markedAt: Long,
    val calendarId: Long? = null,
    /** Kalenteritapahtuma jota merkintä koskee. Null jos kalenteriin ei kirjoitettu. */
    val eventId: Long? = null,
    /** True jos tapahtuma luotiin tätä merkintää varten (päivässä ei ollut vuoroa). */
    val eventCreated: Boolean = false,
    /** Olemassa olleen tapahtuman otsikko ennen ylikirjoitusta. */
    val prevTitle: String? = null,
) {
    val dayType: DayType get() = DayType.fromStorage(type)
    val localDate: java.time.LocalDate get() = java.time.LocalDate.parse(date)
}

@Dao
interface AbsenceDayDao {

    @Query("SELECT * FROM absence_days ORDER BY date")
    suspend fun all(): List<AbsenceDay>

    @Query("SELECT * FROM absence_days WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun inRange(from: String, to: String): List<AbsenceDay>

    @Query("SELECT * FROM absence_days WHERE date = :date LIMIT 1")
    suspend fun byDate(date: String): AbsenceDay?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: AbsenceDay)

    @Query("DELETE FROM absence_days WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM absence_days")
    suspend fun deleteAll()
}

@Dao
interface ScannedDayDao {

    @Query("SELECT * FROM scanned_days WHERE periodKey = :periodKey ORDER BY date")
    suspend fun forPeriod(periodKey: String): List<ScannedDay>

    @Query("SELECT * FROM scanned_days ORDER BY date")
    suspend fun all(): List<ScannedDay>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(days: List<ScannedDay>)

    @Query("DELETE FROM scanned_days WHERE periodKey = :periodKey")
    suspend fun deleteForPeriod(periodKey: String)

    @Query("DELETE FROM scanned_days")
    suspend fun deleteAll()
}

@Dao
interface ScannedPeriodDao {

    @Query("SELECT * FROM scanned_periods ORDER BY rangeStart")
    suspend fun all(): List<ScannedPeriod>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(period: ScannedPeriod)

    @Query("DELETE FROM scanned_periods WHERE batchId = :batchId")
    suspend fun deleteByBatch(batchId: Long)

    @Query("DELETE FROM scanned_periods WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM scanned_periods")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scanned_periods")
    suspend fun count(): Int

    /** Jaksot jotka menevät päällekkäin annetun välin kanssa. ISO-päiväys vertautuu merkkijonona. */
    @Query("SELECT * FROM scanned_periods WHERE rangeStart <= :to AND rangeEnd >= :from")
    suspend fun overlapping(from: String, to: String): List<ScannedPeriod>
}

@Dao
interface SyncedShiftDao {

    @Query("SELECT * FROM synced_shifts WHERE calendarId = :calendarId AND localDate BETWEEN :from AND :to")
    suspend fun inRange(calendarId: Long, from: String, to: String): List<SyncedShift>

    /**
     * Päivän vuoro **mistä tahansa kalenterista**.
     *
     * Poissaoloa merkittäessä ei voi rajata valittuun kalenteriin: käyttäjä valitsee
     * kalenterin sitä varten että uusi tapahtuma menisi oikeaan paikkaan, mutta jo
     * olemassa oleva vuoro on siinä kalenterissa johon se aikanaan kirjoitettiin.
     * Rajaus valittuun kalenteriin sai merkinnän luulemaan ettei vuoroa ole, jolloin
     * vuoron viereen syntyi turha koko päivän tapahtuma.
     */
    @Query("SELECT * FROM synced_shifts WHERE localDate = :date ORDER BY startMillis LIMIT 1")
    suspend fun byDate(date: String): SyncedShift?

    /** Kalenteri johon sovellus on kirjoittanut eniten vuoroja. Käytetään oletukseksi. */
    @Query(
        "SELECT calendarId FROM synced_shifts GROUP BY calendarId " +
            "ORDER BY COUNT(*) DESC LIMIT 1"
    )
    suspend fun mostUsedCalendarId(): Long?

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
    entities = [
        SyncedShift::class, SyncBatch::class, SyncAction::class,
        ScannedPeriod::class, ScannedDay::class, AbsenceDay::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class ShiftDatabase : RoomDatabase() {
    abstract fun syncedShifts(): SyncedShiftDao
    abstract fun syncBatches(): SyncBatchDao
    abstract fun scannedPeriods(): ScannedPeriodDao
    abstract fun scannedDays(): ScannedDayDao
    abstract fun absenceDays(): AbsenceDayDao

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

        /** v3 -> v4: päiväkohtainen data kalenterinäkymää varten + vero ja vähennykset. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `scanned_days` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`periodKey` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`code` TEXT, " +
                        "`isFree` INTEGER NOT NULL, " +
                        "`minutes` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_scanned_days_periodKey_date` ON `scanned_days` " +
                        "(`periodKey`, `date`)"
                )
                db.execSQL("ALTER TABLE `scanned_periods` ADD COLUMN `taxCents` INTEGER")
                db.execSQL(
                    "ALTER TABLE `scanned_periods` ADD COLUMN `contributionsCents` INTEGER"
                )
            }
        }

        /**
         * v4 -> v5: poissaolomerkinnät ja vuoroajat päivätauluun.
         *
         * Vuoroajat **täytetään takautuvasti** `synced_shifts`-taulusta. Ilman sitä
         * ennen tätä versiota skannatut jaksot eivät osaisi laskea lisiään uudestaan,
         * kun päivä merkitään sairauslomaksi — ja juuri vanhoihin jaksoihin merkintöjä
         * tehdään, koska sairausloman saa tietää vasta jälkikäteen. Mäppäys on
         * olemassa jokaiselle kalenteriin kirjoitetulle vuorolle, joten osuma on hyvä.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `absence_days` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`date` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`markedAt` INTEGER NOT NULL, " +
                        "`calendarId` INTEGER, " +
                        "`eventId` INTEGER, " +
                        "`eventCreated` INTEGER NOT NULL DEFAULT 0, " +
                        "`prevTitle` TEXT)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_absence_days_date` " +
                        "ON `absence_days` (`date`)"
                )
                db.execSQL("ALTER TABLE `scanned_days` ADD COLUMN `startMillis` INTEGER")
                db.execSQL("ALTER TABLE `scanned_days` ADD COLUMN `endMillis` INTEGER")
                db.execSQL(
                    "UPDATE `scanned_days` SET " +
                        "`startMillis` = (SELECT s.`startMillis` FROM `synced_shifts` s " +
                        "WHERE s.`localDate` = `scanned_days`.`date` LIMIT 1), " +
                        "`endMillis` = (SELECT s.`endMillis` FROM `synced_shifts` s " +
                        "WHERE s.`localDate` = `scanned_days`.`date` LIMIT 1) " +
                        "WHERE `isFree` = 0"
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build().also { instance = it }
        }
    }
}
