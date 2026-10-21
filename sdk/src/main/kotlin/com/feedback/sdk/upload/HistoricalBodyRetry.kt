package com.feedback.sdk.upload

import com.feedback.sdk.db.FeedbackStateMachine
import com.feedback.sdk.db.FeedbackStatus
import com.feedback.sdk.db.dao.FeedbackDao

/** 把瞬时重试预算已耗尽的本地正文显式重新入队。 */
internal class HistoricalBodyRetry(
    private val feedbackDao: FeedbackDao,
    private val enqueueWorker: () -> Unit
) {
    suspend fun requeue(limit:Int=100):Int {
        require(limit in 1..100) { "SDK_HISTORICAL_RETRY_LIMIT_INVALID" }
        val exhausted=feedbackDao.getRetryExhausted(limit)
        exhausted.forEach { feedback ->
            feedbackDao.update(feedback.copy(
                status=FeedbackStateMachine.requireTransition(feedback.status,FeedbackStatus.PENDING_BODY),
                retryCount=0,lastErrorCode=null,updatedAt=System.currentTimeMillis()
            ))
        }
        if(exhausted.isNotEmpty()) enqueueWorker()
        return exhausted.size
    }
}
