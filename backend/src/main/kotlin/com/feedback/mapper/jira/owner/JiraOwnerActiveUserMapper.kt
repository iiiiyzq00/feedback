package com.feedback.mapper.jira.owner

import com.feedback.model.po.jira.owner.JiraOwnerActiveUserPo
import com.feedback.model.po.jira.owner.JiraOwnerDisplayNameKeyPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface JiraOwnerActiveUserMapper {
    fun resolveDisplayNameKeys(@Param("displayNamesJson") displayNamesJson: String): List<JiraOwnerDisplayNameKeyPo>
    fun findActiveByMatchKeys(
        @Param("matchKeys") matchKeys: List<String>,
        @Param("candidateLimit") candidateLimit: Int
    ): List<JiraOwnerActiveUserPo>
}
