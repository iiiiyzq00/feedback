package com.feedback.repository.jiraowner

import com.feedback.mapper.jira.owner.JiraOwnerActiveUserMapper
import com.feedback.mapper.jira.owner.JiraOwnerAssigneeMapper
import com.feedback.mapper.jira.owner.JiraOwnerAssignmentMapper
import com.feedback.mapper.jira.owner.JiraOwnerDispositionEventMapper
import com.feedback.mapper.jira.owner.JiraOwnerSourceMapper
import com.feedback.mapper.jira.owner.JiraOwnerWorkflowMapper
import com.feedback.model.po.jira.owner.JiraOwnerActiveUserPo
import com.feedback.model.po.jira.owner.JiraOwnerAssigneePo
import com.feedback.model.po.jira.owner.JiraOwnerAssignmentPo
import com.feedback.model.po.jira.owner.JiraOwnerDisplayNameKeyPo
import com.feedback.model.po.jira.owner.JiraOwnerInitializationWritePo
import com.feedback.model.po.jira.owner.JiraOwnerSourcePo
import com.feedback.model.po.jira.owner.JiraOwnerWorkflowPo
import org.springframework.stereotype.Repository
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException

data class JiraOwnerActiveUserCandidateGroup(
    val matchKey: String,
    val requestedDisplayNames: List<String>,
    val activeUsers: List<JiraOwnerActiveUserPo>
)

data class JiraOwnerActiveUserCandidateSet(
    val groups: List<JiraOwnerActiveUserCandidateGroup>,
    val overflowed: Boolean
)

/**
 * Jira 经办人首次初始化所需的单表持久化边界。
 *
 * 所有集合在进入 Mapper 前去重、稳定排序并限制为 200 条。Repository 只协调单表 Mapper，
 * 候选资格、唯一用户匹配和跨表状态组合由应用服务完成。
 */
@Repository
class JiraOwnerInitializationRepository(
    private val sourceMapper: JiraOwnerSourceMapper,
    private val assigneeMapper: JiraOwnerAssigneeMapper,
    private val workflowMapper: JiraOwnerWorkflowMapper,
    private val assignmentMapper: JiraOwnerAssignmentMapper,
    private val activeUserMapper: JiraOwnerActiveUserMapper,
    private val dispositionEventMapper: JiraOwnerDispositionEventMapper,
    private val json: ObjectMapper = ObjectMapper()
) {
    fun findJiraSourcePage(afterSourceId: Long, limit: Int): List<JiraOwnerSourcePo> {
        if(afterSourceId < 0 || limit !in 1..MAX_BATCH_SIZE) invalid("JIRA_OWNER_SOURCE_PAGE_INVALID")
        return sourceMapper.findPageAfterId(afterSourceId, limit)
    }

    fun findJiraSources(sourceIds: List<Long>): List<JiraOwnerSourcePo> =
        sourceMapper.findJiraByIds(boundedIds(sourceIds))

    fun findAssignees(sourceIds: List<Long>): List<JiraOwnerAssigneePo> =
        assigneeMapper.findBySourceIds(boundedIds(sourceIds))

    fun lockWorkflows(sourceIds: List<Long>): List<JiraOwnerWorkflowPo> =
        workflowMapper.findBySourceIdsForUpdate(boundedIds(sourceIds))

    fun lockAssignments(sourceIds: List<Long>): List<JiraOwnerAssignmentPo> =
        assignmentMapper.findBySourceIdsForUpdate(boundedIds(sourceIds))

    fun findActiveUserCandidates(displayNames: List<String>): JiraOwnerActiveUserCandidateSet {
        val names = displayNames.distinct().sorted()
        if(names.isEmpty() || names.size > MAX_BATCH_SIZE || names.any(String::isBlank))
            invalid("JIRA_OWNER_DISPLAY_NAMES_INVALID")
        val resolvedKeys = activeUserMapper.resolveDisplayNameKeys(json.writeValueAsString(names))
        validateResolvedKeys(names, resolvedKeys)
        val namesByMatchKey = resolvedKeys.groupBy(
            JiraOwnerDisplayNameKeyPo::matchKey,
            JiraOwnerDisplayNameKeyPo::displayName
        )
        val activeUsers = activeUserMapper.findActiveByMatchKeys(
            namesByMatchKey.keys.sorted(),
            MAX_ACTIVE_USER_CANDIDATES + 1
        )
        if(activeUsers.size > MAX_ACTIVE_USER_CANDIDATES + 1) failed("JIRA_OWNER_ACTIVE_USER_LIMIT_BROKEN")
        val overflowed = activeUsers.size > MAX_ACTIVE_USER_CANDIDATES
        val completeUsers = if (overflowed) emptyList() else activeUsers
        if(completeUsers.any { it.matchKey !in namesByMatchKey }) failed("JIRA_OWNER_ACTIVE_USER_KEY_UNEXPECTED")
        val usersByMatchKey = completeUsers.groupBy(JiraOwnerActiveUserPo::matchKey)
        return JiraOwnerActiveUserCandidateSet(
            groups = namesByMatchKey.entries.sortedBy { it.key }.map { (matchKey, requestedNames) ->
                JiraOwnerActiveUserCandidateGroup(
                    matchKey = matchKey,
                    requestedDisplayNames = requestedNames.sorted(),
                    activeUsers = usersByMatchKey[matchKey].orEmpty().sortedBy(JiraOwnerActiveUserPo::id)
                )
            },
            overflowed = overflowed
        )
    }

    fun insertAssignments(rows: List<JiraOwnerInitializationWritePo>) {
        val commands = boundedWrites(rows)
        if(assignmentMapper.insertBatch(commands) != commands.size) failed("JIRA_OWNER_ASSIGNMENT_INSERT_FAILED")
    }

    fun acceptWorkflows(rows: List<JiraOwnerInitializationWritePo>) {
        val commands = boundedWrites(rows)
        if(workflowMapper.acceptBatch(commands) != commands.size) failed("JIRA_OWNER_WORKFLOW_CAS_FAILED")
    }

    fun insertDispositionEvents(rows: List<JiraOwnerInitializationWritePo>) {
        val commands = boundedWrites(rows)
        if(dispositionEventMapper.insertBatch(commands) != commands.size) failed("JIRA_OWNER_DISPOSITION_EVENT_INSERT_FAILED")
    }

    private fun boundedIds(sourceIds: List<Long>): List<Long> = sourceIds.distinct().sorted().also { ids ->
        if(ids.isEmpty() || ids.size > MAX_BATCH_SIZE || ids.any { it <= 0 }) invalid("JIRA_OWNER_SOURCE_IDS_INVALID")
    }

    private fun boundedWrites(rows: List<JiraOwnerInitializationWritePo>): List<JiraOwnerInitializationWritePo> =
        rows.sortedBy(JiraOwnerInitializationWritePo::sourceId).also { commands ->
            if(commands.isEmpty() || commands.size > MAX_BATCH_SIZE ||
                commands.any { it.sourceId <= 0 || it.ownerUserId <= 0 } ||
                commands.map(JiraOwnerInitializationWritePo::sourceId).distinct().size != commands.size)
                invalid("JIRA_OWNER_WRITES_INVALID")
        }

    private fun validateResolvedKeys(names: List<String>, resolvedKeys: List<JiraOwnerDisplayNameKeyPo>) {
        if(resolvedKeys.size != names.size) failed("JIRA_OWNER_DISPLAY_NAME_KEY_COUNT_MISMATCH")
        if(resolvedKeys.any { it.displayName.isBlank() || it.matchKey.isBlank() })
            failed("JIRA_OWNER_DISPLAY_NAME_KEY_INVALID")
        if(resolvedKeys.map(JiraOwnerDisplayNameKeyPo::displayName).sorted() != names)
            failed("JIRA_OWNER_DISPLAY_NAME_KEY_MISMATCH")
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun failed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)

    companion object {
        const val MAX_BATCH_SIZE = 200
        const val MAX_ACTIVE_USER_CANDIDATES = MAX_BATCH_SIZE * 2
    }
}
