package com.feedback.model.po.knowledge

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class JiraKnowledgeInsertPo(
    val title: String, val contentJson: String, val tagsJson: String, val module: String, val issueType: String,
    val sourceId: Long, val fingerprint: ByteArray, val score: Double, val evidenceJson: String, val actor: Long
)

