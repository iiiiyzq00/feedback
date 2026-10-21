package com.feedback.service.jira

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component

data class JiraFieldMappingContract(
    val templateId: Long,
    val version: String,
    val mapping: Map<String, String>,
    /** target -> Jira customfield id；只有显式进入版本化契约的字段才允许读取。 */
    val controlledCustomFieldIds: Map<String, String> = emptyMap()
) {
    init {
        require(controlledCustomFieldIds.keys.all { it in mapping }) { "JIRA_CUSTOM_FIELD_TARGET_NOT_MAPPED" }
        require(controlledCustomFieldIds.keys.all(JiraApiFieldCatalog::supportsControlledCustomTarget)) {
            "JIRA_CUSTOM_FIELD_TARGET_UNSUPPORTED"
        }
        require(controlledCustomFieldIds.values.all { it.matches(Regex("customfield_[1-9]\\d{0,11}")) }) {
            "JIRA_CUSTOM_FIELD_ID_INVALID"
        }
    }

    val requestedFields: List<String> = mapping.keys.mapNotNull { target ->
        controlledCustomFieldIds[target] ?: JiraApiFieldCatalog.requestedField(target)
    }.distinct()

    fun supportsTarget(target: String): Boolean =
        JiraApiFieldCatalog.supports(target) || controlledCustomFieldIds.containsKey(target)

    fun apiPath(target: String): String? = controlledCustomFieldIds[target] ?: JiraApiFieldCatalog.path(target)
}

object JiraApiFieldCatalog {
    private val controlledCustomTargets = setOf("package_name")
    private val paths = mapOf(
        "source_record_id" to "key", "jira_issue_id" to "id", "title" to "summary",
        "jira_issue_type" to "issuetype", "problem_type" to "issuetype", "status" to "status",
        "project_key" to "project.key", "project_name" to "project.name", "project_type" to "project.projectTypeKey",
        "project_lead" to "project.lead", "project_description" to "project.description", "project_url" to "project.url",
        "priority" to "priority", "resolution" to "resolution", "assignee" to "assignee", "reporter" to "reporter",
        "creator" to "creator", "source_event_time" to "created", "source_update_time" to "updated",
        "last_viewed_at" to "lastViewed", "resolved_at" to "resolutiondate", "affected_version" to "versions",
        "fix_version" to "fixVersions", "function_module" to "components", "due_date" to "duedate",
        "votes" to "votes.votes", "label_1" to "labels", "label_2" to "labels", "label_3" to "labels",
        "description" to "description", "environment" to "environment", "watchers" to "watches.watchCount",
        "worklog_time" to "worklog.total", "original_estimate_raw" to "timeoriginalestimate",
        "remaining_estimate_raw" to "timeestimate", "time_spent_raw" to "timespent", "work_ratio" to "workratio",
        "sigma_original_estimate" to "aggregatetimeoriginalestimate", "sigma_remaining_estimate" to "aggregatetimeestimate",
        "sigma_time_spent" to "aggregatetimespent", "security_level" to "security",
        "inward_duplicate_link" to "issuelinks", "outward_duplicate_link" to "issuelinks", "comment_summary" to "comment"
    )
    fun supports(target: String) = target in paths
    fun path(target: String) = paths[target]
    fun requestedField(target: String) = paths[target]?.takeUnless { it in setOf("key", "id") }?.substringBefore('.')
    fun supportsControlledCustomTarget(target: String) = target in paths || target in controlledCustomTargets
}

@Component
class JiraIssueMapper {
    fun toMappedSourceRow(issue: JiraApiIssue, contract: JiraFieldMappingContract): Map<String, String> = buildMap {
        contract.mapping.forEach { (target, source) ->
            mappedValue(issue, target, contract)?.let { put(source, it) }
        }
        val descriptionSource = contract.mapping["description"]
        if (descriptionSource != null && !containsKey(descriptionSource)) {
            contract.mapping["title"]?.let { titleSource -> get(titleSource)?.let { put(descriptionSource, it) } }
        }
    }

    private fun mappedValue(issue: JiraApiIssue, target: String, contract: JiraFieldMappingContract): String? {
        val path = contract.apiPath(target) ?: return null
        if (path == "key") return issue.key
        if (path == "id") return issue.id
        val node = path.split('.').fold(issue.fields as JsonNode) { current, segment -> current.path(segment) }
        return when {
            target in setOf("assignee", "reporter", "creator", "project_lead") -> personDisplayName(node)
            target in setOf("description", "environment") -> richText(node)
            target == "comment_summary" -> commentSummary(node)
            target in setOf("label_1", "label_2", "label_3") -> node.takeIf(JsonNode::isArray)
                ?.path(listOf("label_1", "label_2", "label_3").indexOf(target))?.asText()?.trim()?.takeIf(String::isNotBlank)
            target == "inward_duplicate_link" -> duplicateLinks(node, inward = true)
            target == "outward_duplicate_link" -> duplicateLinks(node, inward = false)
            target in setOf("source_event_time", "source_update_time", "last_viewed_at", "resolved_at") -> jiraTime(node)
            else -> valueText(node)
        }
    }

    private fun jiraTime(node: JsonNode): String? = valueText(node)?.let { raw ->
        if (Regex(".*[+-]\\d{4}$").matches(raw)) raw.dropLast(2) + ":" + raw.takeLast(2) else raw
    }

    private fun personDisplayName(node:JsonNode):String? {
        if(!node.isObject)return null
        return node.path("displayName").asText().trim().take(100).takeIf(String::isNotBlank)
            ?: sequenceOf("name","key","accountId").map{node.path(it).asText().trim()}.firstOrNull(String::isNotBlank)
    }

    private fun richText(node: JsonNode): String? {
        if (node.isNull || node.isMissingNode) return null
        if (node.isTextual) return node.asText().trim().takeIf(String::isNotBlank)
        val parts = mutableListOf<String>()
        fun visit(current: JsonNode) {
            current.path("text").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)?.let(parts::add)
            current.path("content").takeIf(JsonNode::isArray)?.forEach(::visit)
        }
        visit(node)
        return parts.joinToString(" ").trim().takeIf(String::isNotBlank)
    }

    private fun commentSummary(node: JsonNode): String? {
        val comments = node.path("comments").takeIf(JsonNode::isArray) ?: return null
        return comments.take(5).mapNotNull { richText(it.path("body")) }.joinToString("\n").take(4000).takeIf(String::isNotBlank)
    }

    private fun duplicateLinks(node: JsonNode, inward: Boolean): String? = node.takeIf(JsonNode::isArray)?.mapNotNull { link ->
        val relation = if (inward) link.path("type").path("inward").asText() else link.path("type").path("outward").asText()
        val issue = link.path(if (inward) "inwardIssue" else "outwardIssue")
        issue.path("key").asText().trim().takeIf { it.isNotBlank() && relation.equals("Duplicate", ignoreCase = true) }
    }?.joinToString(",")?.takeIf(String::isNotBlank)

    private fun valueText(node:JsonNode):String? = when {
        node.isMissingNode || node.isNull -> null
        node.isValueNode -> node.asText().trim().takeIf(String::isNotBlank)
        node.isArray -> node.mapNotNull(::valueText).joinToString(",").takeIf(String::isNotBlank)
        node.isObject -> sequenceOf("value","name","key").map { node.path(it).asText().trim() }.firstOrNull(String::isNotBlank)
        else -> null
    }
}
