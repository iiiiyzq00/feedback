package com.feedback.repository

import com.feedback.model.feedback.FeedbackIdentity
import com.feedback.mapper.core.CoreFeedbackIdentityMapper
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class FeedbackIdentityRepository(private val mapper: CoreFeedbackIdentityMapper) {
    fun findById(id: Long): Optional<FeedbackIdentity> = Optional.ofNullable(mapper.findById(id))
    fun findBySourceId(sourceId: Long): List<FeedbackIdentity> = mapper.findBySourceId(sourceId)
    fun findBySourceIdAndIdentityTypeAndIdentityScope(sourceId: Long, identityType: String, identityScope: String) =
        mapper.findExact(sourceId, identityType, identityScope)
    fun save(row: FeedbackIdentity): FeedbackIdentity {
        val affected = if (row.id == 0L) mapper.insert(row) else mapper.update(row)
        check(affected in 0..1) { "FEEDBACK_IDENTITY_SAVE_AFFECTED_ROWS_INVALID" }
        return row
    }
}
