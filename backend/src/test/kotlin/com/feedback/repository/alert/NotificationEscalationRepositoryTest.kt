package com.feedback.repository.alert

import com.feedback.mapper.alert.EscalationAuditMapper
import com.feedback.model.po.alert.EscalationChainPo
import com.feedback.mapper.alert.EscalationConfigMapper
import com.feedback.mapper.alert.EscalationIncidentMapper
import com.feedback.mapper.alert.EscalationOccurrenceMapper
import com.feedback.mapper.alert.EscalationRuleMapper
import com.feedback.mapper.alert.EscalationUserMapper
import com.feedback.mapper.alert.NotificationEscalationMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationEscalationRepositoryTest {
    private val notifications=mockk<NotificationEscalationMapper>()
    private val incidents=mockk<EscalationIncidentMapper>()
    private val repository=NotificationEscalationRepository(
        notifications,mockk<EscalationConfigMapper>(),mockk<EscalationUserMapper>(),
        mockk<EscalationRuleMapper>(),mockk<EscalationOccurrenceMapper>(),
        incidents,mockk<EscalationAuditMapper>()
    )

    @Test fun `target is not failed when chain compare and set loses race`() {
        val chain=chain();every{notifications.advanceTarget(chain,"渠道耗尽")} returns 0

        assertFalse(repository.advanceTarget(chain,"渠道耗尽"))

        verify(exactly=0){notifications.failTarget(any(),any())}
    }

    @Test fun `target failure follows successful chain advance in caller transaction`() {
        val chain=chain();every{notifications.advanceTarget(chain,"渠道耗尽")} returns 1;every{notifications.failTarget(7,2)} returns 1

        assertTrue(repository.advanceTarget(chain,"渠道耗尽"))

        verifyOrder{notifications.advanceTarget(chain,"渠道耗尽");notifications.failTarget(7,2)}
    }

    @Test fun `deadline update rejects acknowledged or concurrently advanced chain`() {
        val chain=chain();every{notifications.updateDeadline(7,2,5,120)} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.updateDeadline(chain,120)}
    }

    @Test fun `target acknowledgement rejects missing or incompatible target row`() {
        every{notifications.acknowledgeTarget(8)} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.acknowledgeTarget(8)}
    }

    @Test fun `notification acknowledgement rejects non escalation notification row`() {
        every{notifications.acknowledgeNotification(99,"DTMF_ACK")} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.acknowledgeNotification(99,"DTMF_ACK")}
    }

    @Test fun `current target progress rejects stale target`() {
        every{notifications.markTargetProgress(8)} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.markTargetProgress(8)}
    }

    @Test fun `alert acknowledgement and cancellation report whether a running chain changed`() {
        every{notifications.acknowledgeAlert(9,21)} returnsMany listOf(1,0)
        every{notifications.cancelAlert(9)} returnsMany listOf(1,0)

        assertTrue(repository.acknowledgeAlert(9,21))
        assertFalse(repository.acknowledgeAlert(9,21))
        assertTrue(repository.cancelAlert(9))
        assertFalse(repository.cancelAlert(9))
    }

    @Test fun `incident severity update rejects missing linked incident`() {
        every{incidents.severity(3,"P0")} returns 0

        assertThrows(com.feedback.common.exception.ServiceException::class.java){repository.updateIncidentSeverity(3,"P0")}
    }

    private fun chain()=EscalationChainPo(id=7,displayId=70,targetOrder=2,channelOrder=5,status="RUNNING")
}
