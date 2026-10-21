package com.feedback.manager.sdk

import com.feedback.service.sdk.*

import com.feedback.model.sdk.SdkIngest

/** SDK 接入记录的最小事务写边界。 */
interface SdkIngestPersistence {
    /** 在短事务中保存已校验的 SDK 接收信封；唯一键竞争后必须权威重读并复核命令指纹。 */
    fun saveReceived(ingest: SdkIngest): SdkIngest
}
