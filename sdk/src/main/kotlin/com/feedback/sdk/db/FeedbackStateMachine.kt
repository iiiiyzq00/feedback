package com.feedback.sdk.db

enum class FeedbackStatus {
    PENDING_BODY,
    PENDING_ATTACHMENTS,
    COMPLETED,
    RETRY_EXHAUSTED,
    PERMANENT_FAILED
}

object FeedbackStateMachine {
    private val transitions = mapOf(
        FeedbackStatus.PENDING_BODY to setOf(
            FeedbackStatus.PENDING_ATTACHMENTS,
            FeedbackStatus.COMPLETED,
            FeedbackStatus.PERMANENT_FAILED
        ),
        FeedbackStatus.PENDING_ATTACHMENTS to setOf(
            FeedbackStatus.COMPLETED,
            FeedbackStatus.PERMANENT_FAILED
        ),
        FeedbackStatus.COMPLETED to emptySet(),
        FeedbackStatus.RETRY_EXHAUSTED to setOf(FeedbackStatus.PENDING_BODY),
        FeedbackStatus.PERMANENT_FAILED to emptySet()
    )

    fun requireTransition(from: String, to: FeedbackStatus): String {
        val source = runCatching { FeedbackStatus.valueOf(from) }
            .getOrElse { throw IllegalStateException("Unknown feedback status: $from") }
        check(to in transitions.getValue(source)) { "Invalid feedback transition: $source -> $to" }
        return to.name
    }
}
