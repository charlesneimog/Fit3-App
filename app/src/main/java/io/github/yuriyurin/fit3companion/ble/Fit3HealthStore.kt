package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.util.AtomicFile
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3HealthRecords
import io.github.yuriyurin.fit3companion.protocol.Fit3PedometerBackSync
import io.github.yuriyurin.fit3companion.protocol.Fit3Sleep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.security.MessageDigest

/** Private local database. Raw packet + snapshot/history are committed before Health ACK.
 * Legacy AtomicFile snapshots remain readable; installing an update never clears them.
 */
object Fit3HealthStore {
    private const val FILE = "health-cache-v1.json"
    private val helpers = HashMap<String, HealthDatabase>()
    private fun database(context: Context): SQLiteDatabase = synchronized(this) {
        val key = context.getDatabasePath("health-v2.db").absolutePath
        helpers.getOrPut(key) { HealthDatabase(context.applicationContext) }.writableDatabase
    }
    private class HealthDatabase(context: Context) : SQLiteOpenHelper(context, "health-v2.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE documents (name TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE packets (hash TEXT PRIMARY KEY, source TEXT NOT NULL, received INTEGER NOT NULL, payload BLOB NOT NULL)")
            Fit3HealthStore.createRecordTables(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                Fit3HealthStore.createRecordTables(db)
                db.rawQuery("SELECT source,received,payload FROM packets ORDER BY received,hash", null).use { rows ->
                    while (rows.moveToNext()) Fit3HealthStore.insertRecords(db, rows.getString(0),
                        Fit3HealthRecords.decode(rows.getBlob(2), rows.getLong(1)), rows.getLong(1))
                }
            }
        }
    }

    private fun createRecordTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS health_records (
            source TEXT NOT NULL,
            metric TEXT NOT NULL,
            aggregation TEXT NOT NULL,
            wire_type INTEGER NOT NULL,
            start INTEGER NOT NULL,
            end INTEGER,
            value REAL NOT NULL,
            minimum REAL,
            maximum REAL,
            group_id TEXT NOT NULL DEFAULT '',
            received INTEGER NOT NULL,
            PRIMARY KEY (source,metric,aggregation,start,group_id)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS health_records_metric_time ON health_records(metric,start)")
        db.execSQL("CREATE INDEX IF NOT EXISTS health_records_source_time ON health_records(source,start)")
    }

    data class WriteResult(
        val decoded: Int,
        val inserted: Int,
        val updated: Int,
        val unchanged: Int,
        val records: List<Fit3HealthRecords.Record>,
    ) {
        fun diagnostic(): String {
            if (records.isEmpty()) return "records=0 stored=0"
            val metrics = records.groupBy { it.metric.storageName }.toSortedMap().entries.joinToString(";") { (name, rows) ->
                val times = rows.map { it.measuredAt }.distinct().sorted()
                val shown = if (times.size <= 12) times.joinToString(",") else
                    "${times.take(5).joinToString(",")},...,${times.takeLast(5).joinToString(",")}"
                "$name=${rows.size}[$shown]"
            }
            return "records=$decoded inserted=$inserted updated=$updated duplicate=$unchanged metrics=$metrics"
        }
    }

    data class StoredRecord(
        val source: String,
        val record: Fit3HealthRecords.Record,
        val received: Long,
    )

    private data class RecordStats(val inserted: Int, val updated: Int, val unchanged: Int)

    private fun insertRecords(
        db: SQLiteDatabase,
        source: String,
        records: List<Fit3HealthRecords.Record>,
        received: Long,
    ): RecordStats {
        var inserted = 0
        var updated = 0
        var unchanged = 0
        records.forEach { record ->
            require(source.length in 1..100 && record.groupId.length <= 100)
            var exists = false
            var same = false
            db.rawQuery("""SELECT wire_type,end,value,minimum,maximum FROM health_records
                WHERE source=? AND metric=? AND aggregation=? AND start=? AND group_id=?""".trimIndent(),
                arrayOf(source, record.metric.storageName, record.aggregation.storageName,
                    record.startMillis.toString(), record.groupId)).use { row ->
                if (row.moveToFirst()) {
                    exists = true
                    fun nullableLong(index: Int) = if (row.isNull(index)) null else row.getLong(index)
                    fun nullableDouble(index: Int) = if (row.isNull(index)) null else row.getDouble(index)
                    same = row.getInt(0) == record.wireType && nullableLong(1) == record.endMillis &&
                        row.getDouble(2) == record.value && nullableDouble(3) == record.minimum &&
                        nullableDouble(4) == record.maximum
                }
            }
            if (same) {
                unchanged++
            } else {
                val row = db.insertWithOnConflict("health_records", null, ContentValues().apply {
                    put("source", source)
                    put("metric", record.metric.storageName)
                    put("aggregation", record.aggregation.storageName)
                    put("wire_type", record.wireType)
                    put("start", record.startMillis)
                    if (record.endMillis == null) putNull("end") else put("end", record.endMillis)
                    put("value", record.value)
                    if (record.minimum == null) putNull("minimum") else put("minimum", record.minimum)
                    if (record.maximum == null) putNull("maximum") else put("maximum", record.maximum)
                    put("group_id", record.groupId)
                    put("received", received)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                check(row != -1L) { "Не удалось сохранить запись здоровья" }
                if (exists) updated++ else inserted++
            }
        }
        return RecordStats(inserted, updated, unchanged)
    }

    /** Complete local history, ordered by the watch's measurement time (not sync time). */
    @Synchronized fun records(
        context: Context,
        metric: Fit3HealthRecords.Metric? = null,
        source: String? = null,
        fromMillis: Long = 0L,
        untilMillis: Long = Long.MAX_VALUE,
        limit: Int = 100_000,
    ): List<StoredRecord> {
        require(fromMillis >= 0 && untilMillis >= fromMillis && limit in 1..100_000)
        val where = mutableListOf("start>=?", "start<?")
        val args = mutableListOf(fromMillis.toString(), untilMillis.toString())
        metric?.let { where += "metric=?"; args += it.storageName }
        source?.let { require(it.length in 1..100); where += "source=?"; args += it }
        args += limit.toString()
        val result = ArrayList<StoredRecord>()
        database(context).rawQuery("""SELECT source,metric,aggregation,wire_type,start,end,value,
            minimum,maximum,group_id,received FROM health_records
            WHERE ${where.joinToString(" AND ")} ORDER BY start,metric,aggregation LIMIT ?""".trimIndent(),
            args.toTypedArray()).use { rows ->
            while (rows.moveToNext()) {
                val rowMetric = Fit3HealthRecords.Metric.values().firstOrNull {
                    it.storageName == rows.getString(1)
                } ?: continue
                val aggregation = Fit3HealthRecords.Aggregation.values().firstOrNull {
                    it.storageName == rows.getString(2)
                } ?: continue
                result += StoredRecord(rows.getString(0), Fit3HealthRecords.Record(
                    metric = rowMetric,
                    aggregation = aggregation,
                    wireType = rows.getInt(3),
                    startMillis = rows.getLong(4),
                    endMillis = if (rows.isNull(5)) null else rows.getLong(5),
                    value = rows.getDouble(6),
                    minimum = if (rows.isNull(7)) null else rows.getDouble(7),
                    maximum = if (rows.isNull(8)) null else rows.getDouble(8),
                    groupId = rows.getString(9),
                ), rows.getLong(10))
            }
        }
        return result
    }

    fun load(context: Context): Fit3HealthCodec.State = runCatching {
        val document = readDocument(context) ?: error("Нет сохранённых данных")
        var state = Fit3PedometerBackSync.reconcile(decode(document), System.currentTimeMillis())
        if (!document.has("sleepEpisodes")) {
            // Reconstruct old sleep batches from the retained archive, without replaying steps.
            database(context).rawQuery("SELECT payload FROM packets ORDER BY received,hash", null).use { rows ->
                while (rows.moveToNext()) {
                    val sleep = Fit3Sleep.merge(state.sleepEpisodes, state.sleepStages, rows.getBlob(0), System.currentTimeMillis())
                    state = state.copy(sleepEpisodes = sleep.episodes, sleepStages = sleep.stages)
                }
            }
            save(context, state)
        }
        // b24/b25 stored the sender on each packet, but not on the snapshot.
        // Migrate only an unambiguous real Bluetooth source; never infer a new watch.
        if (state.stepSourceAddress == null) {
            val sources = mutableSetOf<String>()
            database(context).rawQuery("SELECT DISTINCT source FROM packets", null).use { rows ->
                while (rows.moveToNext()) rows.getString(0).takeIf {
                    it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
                }?.let(sources::add)
            }
            if (sources.size == 1) state = state.copy(stepSourceAddress = sources.single())
        }
        document.optJSONObject("dailySteps")?.let { daily ->
            val editor = context.getSharedPreferences("fit3_daily_steps", Context.MODE_PRIVATE).edit()
            daily.keys().forEach { day -> daily.optInt(day, -1).takeIf { it in 0..250_000 }
                ?.let { editor.putInt(day, it) } }
            editor.apply()
        }
        if (state.stepDay != null && state.steps != null) {
            context.getSharedPreferences("fit3_daily_steps", Context.MODE_PRIVATE).edit()
                .putInt(state.stepDay.toString(), state.steps).apply()
        }
        if (state.sleepEpisodes.isNotEmpty()) state = state.copy(sleepMinutes = Fit3Sleep.todayMinutes(
            state.sleepEpisodes, state.sleepStages, System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
        state
    }.getOrElse { recoverVitalsFromDiagnosticLog(context) }

    /** One-time upgrade path for measurements received by older builds before persistence existed. */
    private fun recoverVitalsFromDiagnosticLog(context: Context): Fit3HealthCodec.State {
        val log = File(context.filesDir, "protocol-monitor.log")
        if (!log.isFile || log.length() > 4_000_000) return Fit3HealthCodec.State()
        var state = Fit3HealthCodec.State()
        runCatching {
            log.forEachLine { line ->
                val marker = " RX S10/Health [SYNC_DATA REQ]  "
                val at = line.indexOf(marker)
                if (at < 0) return@forEachLine
                val bytes = line.substring(at + marker.length).trim().split(' ')
                    .mapNotNull { it.toIntOrNull(16)?.takeIf { value -> value in 0..255 } }
                if (bytes.size < 12 || bytes[5] !in setOf(10, 16, 19, 112)) return@forEachLine
                val timestamp = line.substring(0, at).toLongOrNull() ?: return@forEachLine
                val next = Fit3HealthCodec.handle(state, bytes.map(Int::toByte).toByteArray(), timestamp).state
                if (next.lastSyncMillis != null) state = next
            }
        }
        if (state.lastSyncMillis != null) runCatching { save(context, state) }
        return state
    }

    @Synchronized fun save(context: Context, state: Fit3HealthCodec.State) {
        writeDocument(context, documentWithHistory(context, state))
    }

    private fun documentWithHistory(context: Context, state: Fit3HealthCodec.State,
                                    records: List<Fit3HealthRecords.Record> = emptyList()): JSONObject {
        val previous = readDocument(context)
        val document = encode(state)
        val history = previous?.optJSONObject("history") ?: JSONObject()
        appendMeasurement(history, "heartRate", state.heartRateAt, state.heartRate)
        appendMeasurement(history, "stress", state.stressAt, state.stress)
        appendMeasurement(history, "spo2", state.spo2At, state.spo2)
        appendMeasurement(history, "sleepMinutes", state.sleepEndAt, state.sleepMinutes)
        // This legacy JSON view remains available to backups. SQLite health_records below is
        // authoritative and unbounded; these points retain their watch timestamps.
        records.forEach { record ->
            val name = when (record.metric) {
                Fit3HealthRecords.Metric.STEPS -> "steps"
                Fit3HealthRecords.Metric.HEART_RATE -> "heartRate"
                Fit3HealthRecords.Metric.STRESS -> "stress"
                Fit3HealthRecords.Metric.SPO2 -> "spo2"
                Fit3HealthRecords.Metric.SLEEP_DURATION_MINUTES -> "sleepMinutes"
                else -> null
            }
            if (name != null) appendMeasurement(history, name, record.measuredAt,
                record.value.toInt())
        }
        document.put("history", history)
        val daily = previous?.optJSONObject("dailySteps") ?: JSONObject()
        if (state.stepDay != null && state.steps != null) daily.put(state.stepDay.toString(), state.steps)
        document.put("dailySteps", daily)
        return document
    }

    /** The transaction is the acceptance boundary: any failure prevents the caller's ACK. */
    @Synchronized fun accept(context: Context, source: String, state: Fit3HealthCodec.State,
                             payload: ByteArray, received: Long): WriteResult {
        require(payload.size in 1..1_048_576)
        val records = Fit3HealthRecords.decode(payload, received)
        val document = documentWithHistory(context, state, records)
        val db = database(context)
        var stats = RecordStats(0, 0, 0)
        db.beginTransaction()
        try {
            db.insertWithOnConflict("packets", null, ContentValues().apply {
                put("hash", packetHash(source, payload)); put("source", source)
                put("received", received); put("payload", payload)
            }, SQLiteDatabase.CONFLICT_IGNORE).let {
                // -1 can be an existing hash. Ensure the row really exists, not a failed write.
                if (it == -1L) db.rawQuery("SELECT 1 FROM packets WHERE hash=?",
                    arrayOf(packetHash(source, payload))).use { cursor -> require(cursor.moveToFirst()) }
            }
            stats = insertRecords(db, source, records, received)
            putDocument(db, document)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return WriteResult(records.size, stats.inserted, stats.updated, stats.unchanged, records)
    }

    private fun packetHash(source: String, payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .apply { update(source.toByteArray(Charsets.UTF_8)); update(0.toByte()); update(payload) }
        .digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Synchronized fun export(context: Context, state: Fit3HealthCodec.State): JSONObject = encode(state).apply {
        readDocument(context)?.optJSONObject("history")?.let { put("history", it) }
        readDocument(context)?.optJSONObject("dailySteps")?.let { put("dailySteps", it) }
        val archive = JSONArray()
        database(context).rawQuery("SELECT source,received,payload FROM packets ORDER BY received,hash", null).use { rows ->
            var bytes = 0L
            while (rows.moveToNext()) {
                val payload = rows.getBlob(2)
                bytes += payload.size
                require(bytes <= 32_000_000 && archive.length() < 100_000) { "Архив здоровья слишком велик для JSON-копии" }
                archive.put(JSONObject().put("source", rows.getString(0)).put("received", rows.getLong(1))
                    .put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)))
            }
        }
        put("packetArchive", archive)
    }

    @Synchronized fun import(context: Context, document: JSONObject) {
        decode(document)
        document.optJSONObject("history")?.let { history ->
            for (name in listOf("steps", "heartRate", "stress", "spo2", "sleepMinutes")) {
                val points = history.optJSONArray(name) ?: continue
                require(points.length() <= 10_000) { "Слишком много записей здоровья" }
                for (index in 0 until points.length()) {
                    val point = points.optJSONObject(index) ?: error("Повреждена история здоровья")
                    require(point.optLong("at", -1) >= 0 && point.optInt("value", -1) >= 0)
                }
            }
        }
        val archive = document.optJSONArray("packetArchive")
        require(archive == null || archive.length() <= 100_000)
        var total = 0L
        val packets = (0 until (archive?.length() ?: 0)).map { index ->
            val entry = archive!!.getJSONObject(index)
            val source = entry.getString("source")
            require(source.length <= 100 && entry.getLong("received") >= 0)
            val payload = Base64.decode(entry.getString("payload"), Base64.NO_WRAP)
            total += payload.size
            require(payload.size in 1..1_048_576 && total <= 32_000_000)
            Triple(source, entry.getLong("received"), payload)
        }
        val db = database(context)
        db.beginTransaction()
        try {
            // Merge the packet archive: restoring a copy must not erase later local records.
            packets.forEach { (source, at, payload) ->
                val hash = packetHash(source, payload)
                val row = db.insertWithOnConflict("packets", null, ContentValues().apply {
                    put("hash", packetHash(source, payload)); put("source", source)
                    put("received", at); put("payload", payload)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (row == -1L) db.rawQuery("SELECT 1 FROM packets WHERE hash=?", arrayOf(hash))
                    .use { require(it.moveToFirst()) }
                insertRecords(db, source, Fit3HealthRecords.decode(payload, at), at)
            }
            val snapshot = JSONObject(document.toString()).apply { remove("packetArchive") }
            val oldSleep = readDocument(context)?.let(::decode)
            val restored = decode(snapshot)
            var sleep = Fit3Sleep.Records(
                (oldSleep?.sleepEpisodes.orEmpty() + restored.sleepEpisodes).associateBy { it.id }.values.toList(),
                (oldSleep?.sleepStages.orEmpty() + restored.sleepStages)
                    .associateBy { Triple(it.sleepId, it.start, it.end) }.values.toList())
            db.rawQuery("SELECT payload FROM packets ORDER BY received,hash", null).use { rows ->
                while (rows.moveToNext()) sleep = Fit3Sleep.merge(sleep.episodes, sleep.stages,
                    rows.getBlob(0), System.currentTimeMillis())
            }
            val sleepJson = encode(restored.copy(sleepEpisodes = sleep.episodes, sleepStages = sleep.stages))
            snapshot.put("sleepEpisodes", sleepJson.getJSONArray("sleepEpisodes"))
            snapshot.put("sleepStages", sleepJson.getJSONArray("sleepStages"))
            putDocument(db, snapshot)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun appendMeasurement(history: JSONObject, name: String, at: Long?, value: Int?) {
        if (at == null || value == null || at < 0) return
        val points = history.optJSONArray(name) ?: JSONArray()
        // A repeated/corrected timestamp replaces the same measurement instead of duplicating it.
        for (index in points.length() - 1 downTo 0) {
            if (points.optJSONObject(index)?.optLong("at") == at) {
                points.put(index, JSONObject().put("at", at).put("value", value))
                history.put(name, points)
                return
            }
        }
        val last = points.optJSONObject(points.length() - 1)
        if (name == "steps" && last?.optInt("value") == value &&
            at - last.optLong("at") in 0 until 24 * 60 * 60_000L) return
        points.put(JSONObject().put("at", at).put("value", value))
        val bounded = JSONArray()
        for (index in maxOf(0, points.length() - 10_000) until points.length())
            bounded.put(points.get(index))
        history.put(name, bounded)
    }

    @Synchronized private fun readDocument(context: Context): JSONObject? {
        database(context).rawQuery("SELECT json FROM documents WHERE name='snapshot'", null).use {
            if (it.moveToFirst()) return JSONObject(it.getString(0))
        }
        return runCatching {
            val raw = AtomicFile(File(context.filesDir, FILE)).openRead().bufferedReader().use { it.readText() }
            JSONObject(raw)
        }.getOrNull()
    }

    private fun writeDocument(context: Context, document: JSONObject) {
        putDocument(database(context), document)
    }

    private fun putDocument(db: SQLiteDatabase, document: JSONObject) {
        val row = db.insertWithOnConflict("documents", null, ContentValues().apply {
            put("name", "snapshot"); put("json", document.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(row != -1L) { "Не удалось сохранить данные здоровья" }
    }

    fun encode(state: Fit3HealthCodec.State): JSONObject = JSONObject().apply {
        put("version", 2)
        fun number(name: String, value: Number?) { if (value != null) put(name, value) }
        number("steps", state.steps)
        number("walkSteps", state.walkSteps)
        number("runSteps", state.runSteps)
        number("stepGoal", state.stepGoal)
        number("distanceMeters", state.distanceMeters)
        number("activeCalories", state.activeCalories)
        number("activeMinutes", state.activeMinutes)
        number("floors", state.floors)
        number("heartRate", state.heartRate)
        number("heartRateMin", state.heartRateMin)
        number("heartRateMax", state.heartRateMax)
        number("heartRateAt", state.heartRateAt)
        number("stress", state.stress)
        number("stressMin", state.stressMin)
        number("stressMax", state.stressMax)
        number("stressAt", state.stressAt)
        number("spo2", state.spo2)
        number("spo2At", state.spo2At)
        number("sleepMinutes", state.sleepMinutes)
        number("sleepScore", state.sleepScore)
        number("sleepEndAt", state.sleepEndAt)
        put("sleepEpisodes", JSONArray().apply {
            state.sleepEpisodes.forEach { put(JSONObject().put("id", it.id).put("start", it.start).put("end", it.end)) }
        })
        put("sleepStages", JSONArray().apply {
            state.sleepStages.forEach { put(JSONObject().put("id", it.sleepId).put("start", it.start)
                .put("end", it.end).put("kind", it.kind)) }
        })
        number("stepDay", state.stepDay)
        state.stepSourceAddress?.let { put("stepSourceAddress", it) }
        number("lastSyncMillis", state.lastSyncMillis)
        number("lastSuccessfulSyncMillis", state.lastSuccessfulSyncMillis)
        number("historySyncCursorMillis", state.historySyncCursorMillis)
        put("stepsPartial", state.stepsPartial)
        put("stepHistory", JSONArray(state.stepHistory.takeLast(96)))
        put("stepRecords", JSONArray().apply {
            state.stepRecords.entries.toList().takeLast(2048).forEach { (time, record) ->
                put(JSONObject().apply {
                    put("time", time)
                    put("count", record.count)
                    put("walk", record.walk)
                    put("run", record.run)
                    put("distanceMeters", record.distanceMeters)
                    put("calories", record.calories)
                    put("durationMillis", record.durationMillis)
                })
            }
        })
    }

    fun decode(json: JSONObject): Fit3HealthCodec.State {
        require(json.optInt("version") in 1..2) { "Неподдерживаемая версия данных здоровья" }
        fun int(name: String, range: IntRange): Int? = json.opt(name)?.let {
            (it as? Number)?.toInt()?.takeIf(range::contains)
        }
        fun long(name: String): Long? = (json.opt(name) as? Number)?.toLong()?.takeIf { it >= 0 }
        fun double(name: String, max: Double): Double? = (json.opt(name) as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..max }
        val records = linkedMapOf<Long, Fit3HealthCodec.StepRecord>()
        json.optJSONArray("stepRecords")?.let { array ->
            require(array.length() <= 2048) { "Слишком много интервалов шагов" }
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val time = item.optLong("time")
                val count = item.optInt("count", -1)
                if (time < 0 || count !in 0..100_000) continue
                val walk = item.optInt("walk", 0)
                val run = item.optInt("run", 0)
                val distance = item.optDouble("distanceMeters", 0.0)
                val calories = item.optDouble("calories", 0.0)
                val duration = item.optInt("durationMillis", 0)
                if (walk !in 0..count || run !in 0..count || !distance.isFinite() ||
                    distance !in 0.0..100_000.0 || !calories.isFinite() ||
                    calories !in 0.0..10_000.0 || duration !in 0..86_400_000) continue
                records[time] = Fit3HealthCodec.StepRecord(count, walk, run, distance, calories, duration)
            }
        }
        val history = json.optJSONArray("stepHistory")?.let { array ->
            (0 until minOf(array.length(), 96)).mapNotNull { i -> array.optInt(i, -1).takeIf { it in 0..250_000 } }
        } ?: emptyList()
        return Fit3HealthCodec.State(
            steps = int("steps", 0..250_000), walkSteps = int("walkSteps", 0..250_000),
            runSteps = int("runSteps", 0..250_000), stepGoal = int("stepGoal", 1..250_000),
            distanceMeters = double("distanceMeters", 300_000.0),
            activeCalories = double("activeCalories", 20_000.0),
            activeMinutes = int("activeMinutes", 0..1440) ?: records.values.takeIf {
                it.isNotEmpty() && long("stepDay") == LocalDate.now().toEpochDay()
            }?.let { intervals ->
                (intervals.sumOf { it.durationMillis.toLong() } / 60_000L).coerceIn(0L, 1440L).toInt()
            }, floors = double("floors", 500.0),
            heartRate = int("heartRate", 25..250), heartRateMin = int("heartRateMin", 25..250),
            heartRateMax = int("heartRateMax", 25..250), heartRateAt = long("heartRateAt"),
            stress = int("stress", 0..100), stressMin = int("stressMin", 0..100),
            stressMax = int("stressMax", 0..100), stressAt = long("stressAt"),
            spo2 = int("spo2", 50..100), spo2At = long("spo2At"),
            sleepMinutes = int("sleepMinutes", 0..2160), sleepScore = int("sleepScore", 0..100),
            sleepEndAt = long("sleepEndAt"), stepHistory = history, stepRecords = records,
            sleepEpisodes = sleepRows(json, "sleepEpisodes").map { Fit3Sleep.Episode(it.getString("id"), it.getLong("start"), it.getLong("end")) },
            sleepStages = sleepRows(json, "sleepStages").map { Fit3Sleep.Stage(it.getString("id"), it.getLong("start"), it.getLong("end"), it.getInt("kind")) },
            stepSourceAddress = json.optString("stepSourceAddress").takeIf {
                it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            },
            stepsPartial = json.optBoolean("stepsPartial"), stepDay = long("stepDay"),
            lastSyncMillis = long("lastSyncMillis"),
            lastSuccessfulSyncMillis = long("lastSuccessfulSyncMillis"),
            historySyncCursorMillis = long("historySyncCursorMillis"),
        )
    }

    private fun sleepRows(json: JSONObject, name: String): List<JSONObject> {
        val rows = json.optJSONArray(name) ?: return emptyList()
        require(rows.length() <= 100000)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.onEach {
            require(it.getString("id").length in 1..100)
            val start = it.getLong("start")
            val end = it.getLong("end")
            require(start >= 946684800000L && end > start && end - start <= 36 * 3600000L)
        }
    }
}
