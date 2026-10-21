package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.online.StationOnlineChannelPo
import com.feedback.model.po.station.online.StationOnlineSourcePo
import com.feedback.repository.station.online.StationOnlineChannelRepository
import com.feedback.repository.station.online.StationOnlineContentRepository
import com.feedback.repository.station.online.StationOnlineSourceRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/** Station 受理短事务的幂等复核与原子写入单元合同。 */
class StationFeedbackIngestCommitWorkerTest {
    private val sources = mockk<StationOnlineSourceRepository>()
    private val contents = mockk<StationOnlineContentRepository>(relaxed = true)
    private val channels = mockk<StationOnlineChannelRepository>(relaxed = true)
    private val identities = mockk<StationOnlineIdentityService>(relaxed = true)
    private val raw = mockk<StationRawPayloadService>(relaxed = true)
    private val dirty = mockk<FeedbackListProjectionDirtyMarker>(relaxed = true)
    private val worker = StationFeedbackIngestCommitWorker(sources, contents, channels, identities, raw, dirty)

    /** 唯一键定位到相同指纹时稳定重放且不重复写正式事实。 */
    @Test
    fun `same command replays existing receipt`() {
        val command = command()
        every { sources.insertOrLocate(any()) } answers {
            firstArg<StationOnlineSourcePo>().id = 9
            2
        }
        every { channels.find(9) } returns StationOnlineChannelPo(
            sourceId = 9,
            receiptId = ByteArray(16) { 1 },
            requestFingerprint = command.requestFingerprint,
            authenticatedProductCode = "product"
        )
        every { raw.storePrepared(any(), any()) } just runs

        val result = worker.commit(command)

        assertEquals(9, result.sourceId)
        verify(exactly = 0) { contents.insert(any()) }
        verify(exactly = 1) { raw.storePrepared(9, command.rawPayload) }
    }

    /** 唯一键定位到不同指纹时失败关闭且不写后续事实。 */
    @Test
    fun `different command on same key conflicts after locate`() {
        val command = command()
        every { sources.insertOrLocate(any()) } answers {
            firstArg<StationOnlineSourcePo>().id = 9
            2
        }
        every { channels.find(9) } returns StationOnlineChannelPo(
            sourceId = 9,
            receiptId = ByteArray(16),
            requestFingerprint = ByteArray(32) { 8 },
            authenticatedProductCode = "product"
        )

        val error = assertThrows(ServiceException::class.java) { worker.commit(command) }

        assertEquals("STATION_IDEMPOTENCY_CONFLICT", error.reasonCode)
        verify(exactly = 0) { contents.insert(any()) }
        verify(exactly = 0) { raw.storePrepared(any(), any()) }
    }

    /** 构造不含明文敏感信息的最小预备命令。 */
    private fun command(): PreparedStationIngest {
        val now = LocalDateTime.of(2026, 9, 9, 10, 0)
        return PreparedStationIngest(
            receiptId = ByteArray(16),
            sourceRecordId = "station-request-1",
            appVersion = "1.0",
            occurredAt = null,
            faultTimeRaw = null,
            faultTimePrecision = null,
            faultTimeSource = null,
            faultTimeResolution = "MISSING",
            now = now,
            safeTitle = "安全标题",
            safeContent = "安全正文",
            safeDescription = null,
            maskingStatus = "PASS",
            sourceApiVersion = "V2",
            productId = "product",
            sourceAppId = "app",
            sourceAppName = "应用",
            sourceUserId = "user",
            authenticatedUserId = "user",
            authenticatedProductCode = "product",
            sourcePlatform = "ANDROID",
            sourceFeedbackType = "BUG",
            sourceSubChannel = "APP",
            pendingPhotoUrls = null,
            sourcePhoneCiphertext = null,
            contactStatus = "NOT_PROVIDED",
            requestFingerprint = ByteArray(32) { 3 },
            rawPayload = PreparedStationRawPayload(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), "key", now.plusDays(30)),
            identity = null
        )
    }
}
