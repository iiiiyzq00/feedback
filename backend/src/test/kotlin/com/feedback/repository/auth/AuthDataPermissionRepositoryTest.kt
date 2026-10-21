package com.feedback.repository.auth

import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthDataPermissionMapper
import com.feedback.model.po.auth.AuthDataPermissionPo
import com.feedback.repository.impl.auth.MyBatisAuthDataPermissionRepositoryImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AuthDataPermissionRepositoryTest {
    private val mapper=mockk<AuthDataPermissionMapper>()
    private val repository=MyBatisAuthDataPermissionRepositoryImpl(mapper)

    @Test fun `empty roles use bounded USER compatibility lookup`() {
        every { mapper.findReadPermissions("7", setOf("USER"), 1_001) } returns listOf(row(1))

        assertEquals(1,repository.findReadPermissions(7,emptySet()).size)
        verify(exactly=1) { mapper.findReadPermissions("7",setOf("USER"),1_001) }
    }

    @Test fun `permission overflow fails closed`() {
        every { mapper.findReadPermissions("7",setOf("USER"),1_001) } returns (1L..1_001L).map(::row)
        val rowsError=assertThrows(ServiceException::class.java) { repository.findReadPermissions(7,emptySet()) }
        assertEquals("AUTH_DATA_PERMISSION_SET_INCOMPLETE",rowsError.reasonCode)
    }

    private fun row(id:Long)=AuthDataPermissionPo(id,"ROLE","USER","CHANNEL","SDK","READ","ALLOW")
}
