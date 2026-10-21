package com.feedback.manager.sdk

import com.feedback.model.sdk.SdkIngest
import com.feedback.repository.sdk.SdkIngestRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 只在写入 `t_sdk_ingest` 时开启事务。
 *
 * 验签、Redis 防重放、限流和 DTO 转换均已在调用前完成，因此事务中不会包含外部网络访问。
 */
@Component
class TransactionalSdkIngestPersistence(
    private val repository: SdkIngestRepository
) : SdkIngestPersistence {
    /** 在短事务中保存已校验的 SDK 接收信封；唯一键竞争后必须权威重读并复核命令指纹。 */
    @Transactional
    override fun saveReceived(ingest: SdkIngest): SdkIngest {
        return repository.save(ingest)
    }
}
