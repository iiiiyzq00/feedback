package com.feedback.service.imports

import org.slf4j.LoggerFactory
import com.feedback.repository.ImportBatchRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class ImportBatchClaimService(private val repository: ImportBatchRepository) {
    @Transactional
    fun claim(workerId:String):String? = claimFair(workerId, emptySet())?.first

    @Transactional
    fun claimFair(workerId: String, excludedChannels: Set<String>): Pair<String,String>? =
        repository.claimFair(workerId, excludedChannels, LocalDateTime.now())

    @Transactional
    fun markFailure(batchId: String, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        repository.markFailure(
            batchId, root.javaClass.simpleName,
            "import-worker:${root.javaClass.simpleName}", LocalDateTime.now()
        )
    }
}

@Component
class ImportBatchWorker(
    private val claims: ImportBatchClaimService,
    private val batches: ImportBatchService
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workerId = "import-${UUID.randomUUID()}"

    fun runPending() {
        val servedChannels = linkedSetOf<String>()
        repeat(5) {
            val (batchId, channel) = claims.claimFair(workerId, servedChannels) ?: return
            servedChannels += channel
            runCatching { batches.processBatch(batchId) }
                .onFailure { error ->
                    log.warn("Import batch failed batch_id={} code={}", batchId, error.javaClass.simpleName)
                    claims.markFailure(batchId, error)
                }
        }
    }
}

@Component
class ImportBatchWorkerScheduler(private val worker: ImportBatchWorker) {
    fun runPending() = worker.runPending()
}
