package com.feedback.repository.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentitySafeTextRepositoryImpl

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentitySafeTextMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentitySafeTextPo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ApplicationIdentitySafeTextRepositoryTest {
    private val mapper = mockk<ApplicationIdentitySafeTextMapper>()
    private val repository = MyBatisApplicationIdentitySafeTextRepositoryImpl(mapper)

    @Test
    fun `only mapper returned passing text is exposed`() {
        every { mapper.findPassingBySourceIds(listOf(1L, 2L)) } returns listOf(
            ApplicationIdentitySafeTextPo(1, "知识课程打不开", "连接超时")
        )

        val result = repository.findPassingBySourceIds(listOf(2L, 1L))

        assertEquals(setOf(1L), result.keys)
        assertEquals("知识课程打不开", result.getValue(1).title)
    }

    @Test
    fun `duplicate rows fail closed`() {
        every { mapper.findPassingBySourceIds(listOf(1L)) } returns listOf(
            ApplicationIdentitySafeTextPo(1, "a", null),
            ApplicationIdentitySafeTextPo(1, "b", null)
        )

        val error = assertThrows(ServiceException::class.java) {
            repository.findPassingBySourceIds(listOf(1L))
        }

        assertEquals(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, error.errorCode)
    }
}
