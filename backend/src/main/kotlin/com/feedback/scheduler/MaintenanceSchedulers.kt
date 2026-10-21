package com.feedback.scheduler

import org.slf4j.LoggerFactory
import com.feedback.repository.scheduler.MaintenanceLockRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class MaintenanceSchedulers(
    private val locks: MaintenanceLockRepository,
    private val leases: StaleLeaseRecovery,
    private val ciphers: CipherCleanupTask
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun leases() = locked("feedback_lease_recovery") { leases.recover() }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    fun ciphers() = locked("feedback_cipher_cleanup") { ciphers.cleanup() }

    private fun locked(name: String, action: () -> Unit) {
        if (!locks.acquire(name)) return
        try { action() } catch (error: Exception) { log.error("Maintenance task {} failed", name, error) }
        finally { locks.release(name) }
    }
}
