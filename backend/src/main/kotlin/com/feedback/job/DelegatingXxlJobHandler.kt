package com.feedback.job

import com.xxl.job.core.biz.model.ReturnT
import com.xxl.job.core.handler.IJobHandler
import com.xxl.job.core.log.XxlJobLogger

/** 薄 XXL 适配层：调度归 XXL，事务与幂等留在 service。 */
abstract class DelegatingXxlJobHandler(
    private val action: () -> Unit,
) : IJobHandler() {
    final override fun execute(param: String?): ReturnT<String> = try {
        action()
        ReturnT.SUCCESS
    } catch (error: Exception) {
        XxlJobLogger.log("XXL handler failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        ReturnT(ReturnT.FAIL_CODE, error.javaClass.simpleName)
    }
}
