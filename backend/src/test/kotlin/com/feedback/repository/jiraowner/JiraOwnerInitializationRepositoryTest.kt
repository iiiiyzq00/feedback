package com.feedback.repository.jiraowner

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.jira.owner.JiraOwnerActiveUserMapper
import com.feedback.mapper.jira.owner.JiraOwnerAssigneeMapper
import com.feedback.mapper.jira.owner.JiraOwnerAssignmentMapper
import com.feedback.mapper.jira.owner.JiraOwnerDispositionEventMapper
import com.feedback.mapper.jira.owner.JiraOwnerSourceMapper
import com.feedback.mapper.jira.owner.JiraOwnerWorkflowMapper
import com.feedback.model.po.jira.owner.JiraOwnerActiveUserPo
import com.feedback.model.po.jira.owner.JiraOwnerDisplayNameKeyPo
import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JiraOwnerInitializationRepositoryTest {
    private val sourceMapper = mockk<JiraOwnerSourceMapper>()
    private val assigneeMapper = mockk<JiraOwnerAssigneeMapper>()
    private val workflowMapper = mockk<JiraOwnerWorkflowMapper>()
    private val assignmentMapper = mockk<JiraOwnerAssignmentMapper>()
    private val activeUserMapper = mockk<JiraOwnerActiveUserMapper>()
    private val eventMapper = mockk<JiraOwnerDispositionEventMapper>()
    private val repository = JiraOwnerInitializationRepository(
        sourceMapper,
        assigneeMapper,
        workflowMapper,
        assignmentMapper,
        activeUserMapper,
        eventMapper
    )

    @Test
    fun `rejects more than two hundred source ids before mapper access`() {
        val error=assertThrows(ClientException::class.java) {
            repository.findJiraSources((1L..201L).toList())
        }
        assertEquals(ErrorCode.BAD_REQUEST,error.errorCode)
        assertEquals("JIRA_OWNER_SOURCE_IDS_INVALID",error.reasonCode)
    }

    @Test
    fun `assignment affected row mismatch fails closed`() {
        val writes = listOf(JiraOwnerInitializationWritePo(11, 21))
        every { assignmentMapper.insertBatch(writes) } returns 0

        val error=assertThrows(ServiceException::class.java) {
            repository.insertAssignments(writes)
        }
        assertEquals("JIRA_OWNER_ASSIGNMENT_INSERT_FAILED",error.reasonCode)
    }

    @Test
    fun `workflow affected row mismatch fails closed`() {
        val writes = listOf(JiraOwnerInitializationWritePo(11, 21))
        every { workflowMapper.acceptBatch(writes) } returns 0

        val error=assertThrows(ServiceException::class.java) {
            repository.acceptWorkflows(writes)
        }
        assertEquals("JIRA_OWNER_WORKFLOW_CAS_FAILED",error.reasonCode)
    }

    @Test
    fun `database match keys merge case variants into one ambiguous group`() {
        every { activeUserMapper.resolveDisplayNameKeys("[\"Wang\",\"wang\"]") } returns listOf(
            JiraOwnerDisplayNameKeyPo("Wang", "KEY"),
            JiraOwnerDisplayNameKeyPo("wang", "KEY")
        )
        every {
            activeUserMapper.findActiveByMatchKeys(
                listOf("KEY"),
                JiraOwnerInitializationRepository.MAX_ACTIVE_USER_CANDIDATES + 1
            )
        } returns listOf(
            JiraOwnerActiveUserPo(21, "Wang", "KEY"),
            JiraOwnerActiveUserPo(22, "wang", "KEY")
        )

        val result = repository.findActiveUserCandidates(listOf("wang", "Wang"))

        assertEquals(listOf("Wang", "wang"), result.groups.single().requestedDisplayNames)
        assertEquals(listOf(21L, 22L), result.groups.single().activeUsers.map { it.id })
    }

    @Test
    fun `four hundred and first user is an overflow sentinel`() {
        every { activeUserMapper.resolveDisplayNameKeys("[\"王工\"]") } returns listOf(
            JiraOwnerDisplayNameKeyPo("王工", "KEY")
        )
        every {
            activeUserMapper.findActiveByMatchKeys(
                listOf("KEY"),
                JiraOwnerInitializationRepository.MAX_ACTIVE_USER_CANDIDATES + 1
            )
        } returns (1L..401L).map { JiraOwnerActiveUserPo(it, "王工", "KEY") }

        val result = repository.findActiveUserCandidates(listOf("王工"))

        assertTrue(result.overflowed)
        assertTrue(result.groups.single().activeUsers.isEmpty())
    }

    @Test
    fun `missing database match key fails closed`() {
        every { activeUserMapper.resolveDisplayNameKeys("[\"王工\"]") } returns emptyList()

        val error=assertThrows(ServiceException::class.java) {
            repository.findActiveUserCandidates(listOf("王工"))
        }
        assertEquals("JIRA_OWNER_DISPLAY_NAME_KEY_COUNT_MISMATCH",error.reasonCode)
    }
}
