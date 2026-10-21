package com.feedback.service.jira

import com.feedback.model.po.jira.owner.JiraOwnerActiveUserPo
import com.feedback.model.po.jira.owner.JiraOwnerAssigneePo
import com.feedback.model.po.jira.owner.JiraOwnerAssignmentPo
import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import com.feedback.model.po.jira.owner.JiraOwnerSourcePo
import com.feedback.model.po.jira.owner.JiraOwnerWorkflowPo
import com.feedback.repository.jiraowner.JiraOwnerInitializationRepository
import com.feedback.repository.jiraowner.JiraOwnerActiveUserCandidateGroup
import com.feedback.repository.jiraowner.JiraOwnerActiveUserCandidateSet
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JiraAssigneeOwnerInitializerTest {
    private val repository = mockk<JiraOwnerInitializationRepository>()
    private val dirtyMarker = mockk<FeedbackListProjectionDirtyMarker>()
    private val initializer = JiraAssigneeOwnerInitializer(repository, dirtyMarker)

    @Test
    fun `initializes unique active assignee and marks both projection facts`() {
        val write = JiraOwnerInitializationWritePo(11, 21)
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "王工"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns emptyList()
        every { repository.findActiveUserCandidates(listOf("王工")) } returns candidates(
            "王工",
            JiraOwnerActiveUserPo(21, "王工", "KEY")
        )
        every { repository.insertAssignments(listOf(write)) } just Runs
        every { repository.acceptWorkflows(listOf(write)) } just Runs
        every { repository.insertDispositionEvents(listOf(write)) } just Runs
        every { dirtyMarker.mark(any()) } just Runs

        assertTrue(initializer.initialize(11))

        verifySequence {
            repository.findJiraSources(listOf(11))
            repository.findAssignees(listOf(11))
            repository.lockAssignments(listOf(11))
            repository.lockWorkflows(listOf(11))
            repository.findActiveUserCandidates(listOf("王工"))
            repository.insertAssignments(listOf(write))
            repository.acceptWorkflows(listOf(write))
            repository.insertDispositionEvents(listOf(write))
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(11), FeedbackListProjectionDirtyReason.ASSIGNMENT))
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(11), FeedbackListProjectionDirtyReason.WORKFLOW))
        }
    }

    @Test
    fun `does not overwrite an existing platform assignment`() {
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "王工"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns listOf(JiraOwnerAssignmentPo(11, 99))
        every { repository.findActiveUserCandidates(listOf("王工")) } returns candidates(
            "王工",
            JiraOwnerActiveUserPo(21, "王工", "KEY")
        )

        assertFalse(initializer.initialize(11))

        verify(exactly = 0) { repository.insertAssignments(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    @Test
    fun `ambiguous active display name produces no writes`() {
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "王工"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns emptyList()
        every { repository.findActiveUserCandidates(listOf("王工")) } returns candidates(
            "王工",
            JiraOwnerActiveUserPo(21, "王工", "KEY"),
            JiraOwnerActiveUserPo(22, "王工", "KEY")
        )

        assertFalse(initializer.initialize(11))

        verify(exactly = 0) { repository.insertAssignments(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    @Test
    fun `database equivalent display name variants remain ambiguous`() {
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "Wang"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns emptyList()
        every { repository.findActiveUserCandidates(listOf("Wang")) } returns candidates(
            "Wang",
            JiraOwnerActiveUserPo(21, "Wang", "KEY"),
            JiraOwnerActiveUserPo(22, "wang", "KEY")
        )

        assertFalse(initializer.initialize(11))

        verify(exactly = 0) { repository.insertAssignments(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    @Test
    fun `marker failure propagates after authoritative writes`() {
        val write = JiraOwnerInitializationWritePo(11, 21)
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "王工"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns emptyList()
        every { repository.findActiveUserCandidates(listOf("王工")) } returns candidates(
            "王工",
            JiraOwnerActiveUserPo(21, "王工", "KEY")
        )
        every { repository.insertAssignments(listOf(write)) } just Runs
        every { repository.acceptWorkflows(listOf(write)) } just Runs
        every { repository.insertDispositionEvents(listOf(write)) } just Runs
        every {
            dirtyMarker.mark(
                FeedbackListProjectionDirtyCommand(listOf(11), FeedbackListProjectionDirtyReason.ASSIGNMENT)
            )
        } throws IllegalStateException("DIRTY_FAILED")

        val error = assertThrows(IllegalStateException::class.java) { initializer.initialize(11) }
        assertEquals("DIRTY_FAILED", error.message)
        verify(exactly = 0) {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(11), FeedbackListProjectionDirtyReason.WORKFLOW))
        }
    }

    @Test
    fun `candidate overflow skips the complete batch without writes`() {
        every { repository.findJiraSources(listOf(11)) } returns listOf(JiraOwnerSourcePo(11))
        every { repository.findAssignees(listOf(11)) } returns listOf(JiraOwnerAssigneePo(11, "王工"))
        every { repository.lockWorkflows(listOf(11)) } returns listOf(JiraOwnerWorkflowPo(11, "UNASSIGNED"))
        every { repository.lockAssignments(listOf(11)) } returns emptyList()
        every { repository.findActiveUserCandidates(listOf("王工")) } returns JiraOwnerActiveUserCandidateSet(
            groups = emptyList(),
            overflowed = true
        )

        assertFalse(initializer.initialize(11))

        verify(exactly = 0) { repository.insertAssignments(any()) }
        verify(exactly = 0) { dirtyMarker.mark(any()) }
    }

    private fun candidates(
        requestedName: String,
        vararg users: JiraOwnerActiveUserPo
    ): JiraOwnerActiveUserCandidateSet = JiraOwnerActiveUserCandidateSet(
        groups = listOf(
            JiraOwnerActiveUserCandidateGroup(
                matchKey = "KEY",
                requestedDisplayNames = listOf(requestedName),
                activeUsers = users.toList()
            )
        ),
        overflowed = false
    )
}
