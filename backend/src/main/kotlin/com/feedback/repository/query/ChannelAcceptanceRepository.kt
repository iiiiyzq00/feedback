package com.feedback.repository.query

import com.feedback.mapper.auth.AuthUserMapper
import com.feedback.mapper.query.*
import com.feedback.model.po.query.*
import org.springframework.stereotype.Repository

@Repository
class ChannelAcceptanceRepository(
    private val acceptances: ChannelAcceptanceMapper,
    private val sources: ChannelAcceptanceSourceMapper,
    private val configs: ChannelViewConfigMapper,
    private val users: AuthUserMapper
) {
    fun latest(): Map<String, Map<String, Any?>> {
        val rows = acceptances.findLatest()
        val userIds = rows.map { it.acceptedBy }.toSet()
        val names = if (userIds.isEmpty()) emptyMap() else users.findByIds(userIds).associate { row ->
            requireNotNull(row.id) to (row.displayName?.takeIf(String::isNotBlank) ?: row.username)
        }
        return rows.associate { row -> row.sourceChannel to linkedMapOf(
            "sourceChannel" to row.sourceChannel, "status" to row.status, "comment" to row.comment,
            "acceptedAt" to row.acceptedAt, "acceptedBy" to names[row.acceptedBy]
        ) }
    }
    fun sourceCounts() = sources.counts().associate { row -> row.sourceChannel to mapOf(
        "sourceChannel" to row.sourceChannel, "total" to row.total, "latestDataAt" to row.latestDataAt
    ) }
    fun accept(channel: String, status: String, comment: String, actor: Long) {
        check(acceptances.insert(channel, status, comment, actor) == 1)
    }
    fun published(channel: String) = configs.findPublished(channel)
    fun nextVersion(channel: String) = configs.nextVersion(channel)
    fun draft(channel: String, version: Long, fieldsJson: String, reason: String, actor: Long) {
        check(configs.insertDraft(channel, version, fieldsJson, reason, actor) == 1)
    }
    fun publish(channel: String, version: Long, actor: Long) {
        require(configs.countDraft(channel, version) == 1) { "CHANNEL_VIEW_DRAFT_NOT_FOUND" }
        configs.supersedePublished(channel)
        check(configs.publish(channel, version, actor) == 1) { "CHANNEL_VIEW_DRAFT_NOT_FOUND" }
    }
    fun rollback(channel: String, version: Long, next: Long, reason: String, actor: Long) {
        val row = configs.findVersion(channel, version) ?: throw NoSuchElementException("CHANNEL_VIEW_VERSION_NOT_FOUND")
        configs.supersedePublished(channel)
        check(configs.insertRollback(channel, next, row.fieldsJson, reason, actor) == 1)
    }
    fun history(channel: String) = configs.history(channel)
}
