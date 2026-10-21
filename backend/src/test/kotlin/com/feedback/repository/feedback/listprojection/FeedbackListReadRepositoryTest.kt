package com.feedback.repository.feedback.listprojection

import com.feedback.application.port.feedback.list.*
import com.feedback.mapper.feedback.listprojection.FeedbackListOwnerFactsMapper
import com.feedback.mapper.feedback.listprojection.FeedbackListProjectionReadMapper
import com.feedback.model.query.feedback.list.FeedbackListReadCriteria
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedbackListReadRepositoryTest {
    private val mapper = mockk<FeedbackListProjectionReadMapper>()
    private val repository = FeedbackListReadRepository(mapper, mockk<FeedbackListOwnerFactsMapper>())

    @Test
    fun `adapter converts semantic fuzzy filters only at MyBatis boundary`() {
        val captured = slot<FeedbackListReadCriteria>()
        every { mapper.findPage(7, capture(captured), 20, 0) } returns emptyList()

        repository.page(
            7,
            FeedbackListCriteria(
                allowedChannels = listOf("SDK"),
                keyword = "50%_done=",
                deviceModel = "Pad_1",
                osVersion = "OS%",
                repairRegion = "华南_区"
            ),
            20,
            0
        )

        assertEquals("%50=%=_done==%", captured.captured.keywordPattern)
        assertEquals("%Pad=_1%", captured.captured.deviceModelPattern)
        assertEquals("%OS=%%", captured.captured.osVersionPattern)
        assertEquals("%华南=_区%", captured.captured.repairRegionPattern)
    }
}
