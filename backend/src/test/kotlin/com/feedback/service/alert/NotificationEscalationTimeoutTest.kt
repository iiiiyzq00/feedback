package com.feedback.service.alert

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.model.po.alert.EscalationChainPo
import com.feedback.model.po.alert.EscalationTimeoutPo
import com.feedback.repository.alert.NotificationEscalationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class NotificationEscalationTimeoutTest {
    private val repository=mockk<NotificationEscalationRepository>()

    @Test fun `timeout candidate is rechecked by compare and set before enqueue`() {
        val timeout=EscalationTimeoutPo(7,70,2,3);every{repository.timedOut()} returns listOf(timeout);every{repository.advanceTimedOut(timeout,"确认超时")} returns false
        val transactions=countingTransactions();val service=NotificationEscalationService(repository,jacksonObjectMapper(),transactions.operations)

        service.scanTimeouts()

        assertEquals(1,transactions.count);verify(exactly=0){repository.chain(70)}
    }

    @Test fun `successful timeout transition enqueues next channel in same short transaction`() {
        val timeout=EscalationTimeoutPo(7,70,2,3);every{repository.timedOut()} returns listOf(timeout);every{repository.advanceTimedOut(timeout,"确认超时")} returns true
        every{repository.chain(70)} returns EscalationChainPo(id=7,displayId=70,status="ACKNOWLEDGED")
        val transactions=countingTransactions();val service=NotificationEscalationService(repository,jacksonObjectMapper(),transactions.operations)

        service.scanTimeouts()

        assertEquals(1,transactions.count);verify{repository.chain(70)}
    }

    private fun countingTransactions():CountingTransactions=CountingTransactions()
    private class CountingTransactions{
        var count=0
        val operations:TransactionOperations=object:TransactionOperations{override fun <T:Any?> execute(action:TransactionCallback<T>):T?{count++;return action.doInTransaction(mockk<TransactionStatus>())}}
    }
}
