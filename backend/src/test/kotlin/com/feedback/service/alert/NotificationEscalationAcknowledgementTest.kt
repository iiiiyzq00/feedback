package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.EscalationChainPo
import com.feedback.model.po.alert.EscalationNotificationPo
import com.feedback.model.po.alert.EscalationTargetPo
import com.feedback.repository.alert.NotificationEscalationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class NotificationEscalationAcknowledgementTest {
    private val repository=mockk<NotificationEscalationRepository>()
    private val service=NotificationEscalationService(repository,jacksonObjectMapper(),immediateTransactions())

    @Test fun `notification acknowledgement writes chain target and receipt facts in order`() {
        every{repository.notification(99)} returns EscalationNotificationPo(99,7,70,21,2)
        every{repository.chainInternal(7)} returns EscalationChainPo(id=7,displayId=7,status="RUNNING")
        every{repository.targetByDisplay(70)} returns EscalationTargetPo(id=8,displayId=70)
        every{repository.acknowledgeChain(7,21)} returns true;every{repository.acknowledgeTarget(8)} returns Unit;every{repository.acknowledgeNotification(99,"DTMF_ACK")} returns Unit

        assertTrue(service.acknowledgeByNotification(99,"DTMF_ACK"))

        verifyOrder{repository.acknowledgeChain(7,21);repository.acknowledgeTarget(8);repository.acknowledgeNotification(99,"DTMF_ACK")}
    }

    @Test fun `duplicate acknowledgement leaves subordinate facts unchanged`() {
        every{repository.notification(99)} returns EscalationNotificationPo(99,7,70,21,2)
        every{repository.chainInternal(7)} returns EscalationChainPo(id=7,displayId=7,status="ACKNOWLEDGED")
        every{repository.acknowledgeChain(7,21)} returns false

        assertFalse(service.acknowledgeByNotification(99,"DTMF_ACK"))

        verify(exactly=0){repository.acknowledgeTarget(any());repository.acknowledgeNotification(any(),any())}
    }

    @Test fun `missing referenced target propagates so chain acknowledgement rolls back`() {
        every{repository.notification(99)} returns EscalationNotificationPo(99,7,70,21,2)
        every{repository.chainInternal(7)} returns EscalationChainPo(id=7,displayId=7,status="RUNNING")
        every{repository.acknowledgeChain(7,21)} returns true
        every{repository.targetByDisplay(70)} returns null

        assertThrows(com.feedback.common.exception.ClientException::class.java){service.acknowledgeByNotification(99,"DTMF_ACK")}

        verify{repository.acknowledgeChain(7,21)}
        verify(exactly=0){repository.acknowledgeNotification(any(),any())}
    }

    @Test fun `acknowledgement entry point remains transactional`() {
        val method=NotificationEscalationService::class.java.getDeclaredMethod("acknowledgeByNotification",Long::class.javaPrimitiveType,String::class.java)
        assertTrue(method.isAnnotationPresent(Transactional::class.java))
    }

    @Test fun `alert without a running escalation chain keeps acknowledgement compatible`() {
        every{repository.chainByAlert(9,true)} returns null

        assertFalse(service.acknowledgeAlert(9,21))

        verify(exactly=0){repository.acknowledgeAlert(any(),any())}
    }

    @Test fun `alert chain acknowledgement conflict fails closed`() {
        every{repository.chainByAlert(9,true)} returns EscalationChainPo(id=7,displayId=70,status="RUNNING")
        every{repository.acknowledgeAlert(9,21)} returns false

        assertThrows(com.feedback.common.exception.ServiceException::class.java){service.acknowledgeAlert(9,21)}
    }

    private fun immediateTransactions():TransactionOperations=object:TransactionOperations{override fun <T:Any?> execute(action:TransactionCallback<T>):T?=action.doInTransaction(mockk<TransactionStatus>())}
}
