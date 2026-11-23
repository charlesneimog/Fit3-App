package io.github.yuriyurin.fit3companion.ble

import io.github.yuriyurin.fit3companion.protocol.Fit3HealthCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class NextcloudHealthApiTest {
    @Test fun normalizesOnlyCredentialFreeHttpsServerRoots() {
        assertEquals("https://cloud.example.com/nextcloud",
            NextcloudHealthApi.normalizeServerUrl("  https://CLOUD.example.com/nextcloud/  "))
        assertNull(NextcloudHealthApi.normalizeServerUrl("http://cloud.example.com"))
        assertNull(NextcloudHealthApi.normalizeServerUrl("https://user:secret@cloud.example.com"))
        assertNull(NextcloudHealthApi.normalizeServerUrl("https://cloud.example.com?token=secret"))
    }

    @Test fun mapsOnlyEquivalentNextcloudHealthMetrics() {
        val pulseAt = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
        val oxygenAt = Instant.parse("2026-11-23T12:01:00Z").toEpochMilli()
        val snapshot = BleSnapshot(health = Fit3HealthCodec.State(
            steps = 6_234,
            stepDay = LocalDate.of(2026, 11, 23).toEpochDay(),
            heartRate = 72,
            heartRateAt = pulseAt,
            spo2 = 98,
            spo2At = oxygenAt,
            stress = 31,
            sleepMinutes = 438,
        ))

        val writes = NextcloudHealthApi.writes(snapshot,
            Instant.parse("2026-11-23T12:05:00Z").toEpochMilli())
        assertEquals(3, writes.size)
        assertEquals("PUT", writes[0].method)
        assertEquals("${NextcloudHealthApi.API_PATH}/daily-values/steps/2026-11-23", writes[0].path)
        assertEquals(6_234, writes[0].body["numericValue"])
        assertEquals(listOf("pulse", "oxygen_saturation"),
            writes.drop(1).map { it.body["metricKey"] })
    }

    @Test fun retryOperationIdsAreStableUuidV4Values() {
        val first = NextcloudHealthApi.operationId("pulse", 1_795_435_200_000L)
        assertEquals(first, NextcloudHealthApi.operationId("pulse", 1_795_435_200_000L))
        assertTrue(first != NextcloudHealthApi.operationId("oxygen_saturation", 1_795_435_200_000L))
        val uuid = UUID.fromString(first)
        assertEquals(4, uuid.version())
        assertEquals(2, uuid.variant())
    }
}
