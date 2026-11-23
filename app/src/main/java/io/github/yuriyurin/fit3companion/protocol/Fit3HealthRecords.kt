package io.github.yuriyurin.fit3companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts a Fit3 Health SYNC_DATA frame into timestamped, independently persistable rows.
 *
 * These layouts are the ones used by Samsung's Fit3 plugin.  Parsing is deliberately strict:
 * an unknown field or a truncated record rejects that record type instead of turning an
 * arbitrary payload byte into a health value.
 */
object Fit3HealthRecords {
    enum class Metric(val storageName: String) {
        STEPS("steps"),
        WALK_STEPS("walk_steps"),
        RUN_STEPS("run_steps"),
        DISTANCE_METERS("distance_meters"),
        ACTIVE_CALORIES("active_calories"),
        ACTIVE_MINUTES("active_minutes"),
        ACTIVE_DURATION_MILLIS("active_duration_millis"),
        FLOORS("floors"),
        HEART_RATE("heart_rate"),
        STRESS("stress"),
        SPO2("spo2"),
        SLEEP_DURATION_MINUTES("sleep_duration_minutes"),
        SLEEP_STAGE("sleep_stage"),
    }

    enum class Aggregation(val storageName: String) {
        SAMPLE("sample"),
        INTERVAL("interval"),
        DAILY("daily"),
        EPISODE("episode"),
        STAGE("stage"),
    }

    data class Record(
        val metric: Metric,
        val aggregation: Aggregation,
        val wireType: Int,
        val startMillis: Long,
        val endMillis: Long? = null,
        val value: Double,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val groupId: String = "",
    ) {
        val measuredAt: Long get() = endMillis ?: startMillis
    }

    data class PedometerInterval(
        val startMillis: Long,
        val endMillis: Long,
        val steps: Int,
        val walk: Int,
        val run: Int,
        val distanceMeters: Double,
        val calories: Double,
        val durationMillis: Int,
    )

    data class DailyCalories(
        val dayMillis: Long,
        val activeCalories: Double,
        val activeMinutes: Int,
    )

    data class FloorSample(
        val startMillis: Long,
        val endMillis: Long,
        val floors: Double,
        val uuid: String,
        val raw: List<Pair<Long, Double>>,
    )

    fun decode(packet: ByteArray, received: Long = System.currentTimeMillis()): List<Record> {
        val type = syncType(packet) ?: return emptyList()
        val rows = when (type) {
            Fit3HealthCodec.DATA_PEDO_STEP_COUNT -> pedometerIntervals(packet).orEmpty().flatMap { interval ->
                listOf(
                    Record(Metric.STEPS, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.steps.toDouble()),
                    Record(Metric.WALK_STEPS, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.walk.toDouble()),
                    Record(Metric.RUN_STEPS, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.run.toDouble()),
                    Record(Metric.DISTANCE_METERS, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.distanceMeters),
                    Record(Metric.ACTIVE_CALORIES, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.calories),
                    Record(Metric.ACTIVE_DURATION_MILLIS, Aggregation.INTERVAL, type,
                        interval.startMillis, interval.endMillis, interval.durationMillis.toDouble()),
                    Record(Metric.ACTIVE_MINUTES, Aggregation.INTERVAL, type, interval.startMillis,
                        interval.endMillis, interval.durationMillis / 60_000.0),
                )
            }
            Fit3HealthCodec.DATA_CALORIES_BURNED -> dailyCalories(packet).orEmpty().flatMap { daily ->
                listOf(
                    Record(Metric.ACTIVE_CALORIES, Aggregation.DAILY, type, daily.dayMillis,
                        value = daily.activeCalories),
                    Record(Metric.ACTIVE_MINUTES, Aggregation.DAILY, type, daily.dayMillis,
                        value = daily.activeMinutes.toDouble()),
                )
            }
            Fit3HealthCodec.DATA_FLOORS_CLIMBED -> floorSamples(packet).orEmpty().flatMap { sample ->
                buildList {
                    add(Record(Metric.FLOORS, Aggregation.INTERVAL, type, sample.startMillis,
                        sample.endMillis, sample.floors, groupId = sample.uuid))
                    sample.raw.forEachIndexed { index, (at, value) ->
                        add(Record(Metric.FLOORS, Aggregation.SAMPLE, type, at, value = value,
                            groupId = "${sample.uuid}:$index"))
                    }
                }
            }
            Fit3HealthCodec.DATA_SLEEP_STAGE -> Fit3Sleep.parseStages(packet).orEmpty().map { stage ->
                Record(Metric.SLEEP_STAGE, Aggregation.STAGE, type, stage.start, stage.end,
                    stage.kind.toDouble(), groupId = stage.sleepId)
            }
            Fit3HealthCodec.DATA_HEART_RATE,
            Fit3HealthCodec.DATA_STRESS,
            Fit3HealthCodec.DATA_OXYGEN_SATURATION,
            Fit3HealthCodec.DATA_SLEEP -> vitalRecords(packet)
            else -> emptyList()
        }
        return rows.filter { valid(it, received) }
    }

    fun pedometerIntervals(packet: ByteArray): List<PedometerInterval>? {
        if (syncType(packet) != Fit3HealthCodec.DATA_PEDO_STEP_COUNT) return null
        val result = ArrayList<PedometerInterval>()
        var offset = 6
        repeat(128) {
            if (offset >= packet.size) return null
            val more = packet[offset++].toInt() and 0xff
            if (more !in 0..1) return null
            var start: Long? = null
            var end: Long? = null
            var count: Int? = null
            var walk = 0
            var run = 0
            var distance = 0.0
            var calories = 0.0
            var duration = 0
            val fields = intArrayOf(5, 1, 2, 3, 16, 17, 18, 19, 20, 21, 22, 23)
            for (field in fields) {
                if (offset >= packet.size || (packet[offset++].toInt() and 0xff) != field) return null
                val width = if (field == 23) 1 else 4
                if (offset + width > packet.size) return null
                if (width == 4) {
                    val raw = le32(packet, offset)
                    when (field) {
                        1 -> start = healthTime(raw)
                        2 -> end = healthTime(raw)
                        16 -> count = raw.takeIf { it in 0..100_000 }
                        17 -> distance = Float.fromBits(raw).toDouble()
                        19 -> calories = Float.fromBits(raw).toDouble()
                        20 -> run = raw
                        21 -> walk = raw
                        22 -> duration = raw
                    }
                }
                offset += width
            }
            val a = start ?: return null
            val b = end ?: return null
            val steps = count ?: return null
            if (b < a || b - a > 86_400_000L || walk !in 0..steps || run !in 0..steps ||
                !distance.isFinite() || distance !in 0.0..100_000.0 ||
                !calories.isFinite() || calories !in 0.0..10_000.0 ||
                duration !in 0..86_400_000) return null
            result += PedometerInterval(a, b, steps, walk, run, distance, calories, duration)
            if (more == 0) return result
        }
        return null
    }

    /** Samsung CaloriesBurned (type 23): continuation byte plus seven four-byte fields. */
    fun dailyCalories(packet: ByteArray): List<DailyCalories>? {
        if (syncType(packet) != Fit3HealthCodec.DATA_CALORIES_BURNED) return null
        val result = ArrayList<DailyCalories>()
        var offset = 6
        repeat(128) {
            if (offset >= packet.size) return null
            val more = packet[offset++].toInt() and 0xff
            if (more !in 0..1) return null
            var day: Long? = null
            var calories: Double? = null
            var activeTime: Int? = null
            repeat(7) {
                if (offset + 5 > packet.size) return null
                val field = packet[offset++].toInt() and 0xff
                val raw = le32(packet, offset)
                offset += 4
                when (field) {
                    0x70 -> day = healthTime(raw)
                    0x71 -> calories = Float.fromBits(raw).toDouble()
                    0x72, 0x73, 0x97, 0x99 -> Unit
                    0x74 -> activeTime = raw
                    else -> return null
                }
            }
            val at = day ?: return null
            val cal = calories ?: return null
            val minutes = normalizeActiveMinutes(activeTime ?: return null) ?: return null
            if (!cal.isFinite() || cal !in 0.0..20_000.0) return null
            result += DailyCalories(at, cal, minutes)
            if (more == 0) return result
        }
        return null
    }

    /** Samsung FloorsClimbed (type 141), including each timestamped raw floor bin. */
    fun floorSamples(packet: ByteArray): List<FloorSample>? {
        if (syncType(packet) != Fit3HealthCodec.DATA_FLOORS_CLIMBED) return null
        val result = ArrayList<FloorSample>()
        var offset = 6
        repeat(128) {
            if (offset >= packet.size) return null
            val more = packet[offset++].toInt() and 0xff
            if (more !in 0..1) return null
            var start: Long? = null
            var end: Long? = null
            var floors: Double? = null
            var uuid = ""
            val rawBins = ArrayList<Pair<Long, Double>>()
            repeat(7) {
                if (offset >= packet.size) return null
                when (val field = packet[offset++].toInt() and 0xff) {
                    1, 2, 3, 5, 0x10 -> {
                        if (offset + 4 > packet.size) return null
                        val raw = le32(packet, offset)
                        offset += 4
                        when (field) {
                            1 -> start = healthTime(raw)
                            2 -> end = healthTime(raw)
                            0x10 -> floors = Float.fromBits(raw).toDouble()
                        }
                    }
                    0x11 -> {
                        if (offset + 4 > packet.size) return null
                        val size = le32(packet, offset)
                        offset += 4
                        if (size < 0 || size > 65_536 || size % 8 != 0 || offset + size > packet.size) return null
                        val limit = offset + size
                        while (offset < limit) {
                            val value = Float.fromBits(le32(packet, offset)).toDouble()
                            val at = healthTime(le32(packet, offset + 4))
                            if (!value.isFinite() || value !in 0.0..500.0) return null
                            rawBins += at to value
                            offset += 8
                        }
                    }
                    0x75 -> {
                        if (offset + 16 > packet.size) return null
                        uuid = packet.copyOfRange(offset, offset + 16)
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        offset += 16
                    }
                    else -> return null
                }
            }
            val a = start ?: return null
            val b = end ?: return null
            val value = floors ?: return null
            if (b < a || b - a > 86_400_000L || !value.isFinite() || value !in 0.0..500.0) return null
            result += FloorSample(a, b, value, uuid.ifEmpty { "$a:$b" }, rawBins)
            if (more == 0) return result
        }
        return null
    }

    private fun vitalRecords(packet: ByteArray): List<Record> = Fit3VitalCodec.parse(packet).orEmpty().mapNotNull { sample ->
        val start = sample.startMillis ?: sample.endMillis ?: return@mapNotNull null
        val end = sample.endMillis
        when (sample.type) {
            Fit3HealthCodec.DATA_HEART_RATE -> sample.value?.takeIf { it in 25..250 }?.let {
                Record(Metric.HEART_RATE, Aggregation.SAMPLE, sample.type, start, end, it.toDouble(),
                    sample.min?.takeIf { value -> value in 25..250 }?.toDouble(),
                    sample.max?.takeIf { value -> value in 25..250 }?.toDouble(), sample.uuid.orEmpty())
            }
            Fit3HealthCodec.DATA_STRESS -> sample.value?.takeIf { it in 0..100 }?.let {
                Record(Metric.STRESS, Aggregation.SAMPLE, sample.type, start, end, it.toDouble(),
                    sample.min?.takeIf { value -> value in 0..100 }?.toDouble(),
                    sample.max?.takeIf { value -> value in 0..100 }?.toDouble(), sample.uuid.orEmpty())
            }
            Fit3HealthCodec.DATA_OXYGEN_SATURATION -> sample.value?.takeIf { it in 50..100 }?.let {
                Record(Metric.SPO2, Aggregation.SAMPLE, sample.type, start, end, it.toDouble(),
                    sample.min?.takeIf { value -> value in 50..100 }?.toDouble(),
                    sample.max?.takeIf { value -> value in 50..100 }?.toDouble(), sample.uuid.orEmpty())
            }
            Fit3HealthCodec.DATA_SLEEP -> end?.takeIf { it > start }?.let {
                Record(Metric.SLEEP_DURATION_MINUTES, Aggregation.EPISODE, sample.type, start, it,
                    (it - start) / 60_000.0, groupId = sample.uuid ?: "$start:$it")
            }
            else -> null
        }
    }

    private fun valid(record: Record, received: Long): Boolean {
        val end = record.endMillis ?: record.startMillis
        return record.startMillis >= 946_684_800_000L && end >= record.startMillis &&
            end <= received + 5 * 60_000L && record.value.isFinite() &&
            record.minimum?.isFinite() != false && record.maximum?.isFinite() != false
    }

    private fun syncType(packet: ByteArray): Int? = if (packet.size >= 6 &&
        (packet[0].toInt() and 0xff) == 0x82 && (packet[2].toInt() and 0xff) == 0xE0
    ) packet[5].toInt() and 0xff else null

    private fun healthTime(raw: Int): Long =
        ((raw.toLong() and 0xffff_ffffL) + 631_152_000L) * 1000L

    private fun le32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    internal fun normalizeActiveMinutes(raw: Int): Int? = when {
        raw < 0 -> null
        raw > 172_800 -> raw / 60_000 // milliseconds
        raw > 1_440 -> raw / 60       // seconds
        else -> raw                   // already minutes
    }.coerceIn(0, 1_440)
}
