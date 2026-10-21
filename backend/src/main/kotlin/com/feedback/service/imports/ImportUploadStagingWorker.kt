package com.feedback.service.imports

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class ImportUploadStagingWorker(private val chunks:ImportChunkUploadService) {
    private val log=LoggerFactory.getLogger(javaClass)

    fun runPending() {
        repeat(4) {
            val processed=runCatching { chunks.processOneReady() }
                .onFailure { log.warn("Encrypted import staging failed code={}",it.javaClass.simpleName) }
                .getOrDefault(true)
            if(!processed)return
        }
    }
}
