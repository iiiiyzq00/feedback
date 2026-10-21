package com.feedback.scheduler

import com.feedback.repository.scheduler.MaintenanceLockRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class MaintenanceSchedulersLockTest {
    private val locks=mockk<MaintenanceLockRepository>(relaxed=true)
    private val leases=mockk<StaleLeaseRecovery>(relaxed=true)
    private val ciphers=mockk<CipherCleanupTask>(relaxed=true)
    private val scheduler=MaintenanceSchedulers(locks,leases,ciphers)

    @Test fun `skips task when named lock is unavailable`() {
        every{locks.acquire("feedback_lease_recovery")} returns false
        scheduler.leases()
        verify(exactly=0){leases.recover()};verify(exactly=0){locks.release(any())}
    }

    @Test fun `always releases named lock when task fails`() {
        every{locks.acquire("feedback_cipher_cleanup")} returns true
        every{ciphers.cleanup()} throws IllegalStateException("boom")
        scheduler.ciphers()
        verify(exactly=1){locks.release("feedback_cipher_cleanup")}
    }
}
