package com.feedback.mapper.imports

import com.feedback.model.jira.ChannelJira
import org.apache.ibatis.annotations.Mapper

@Mapper interface ChannelJiraMapper { fun upsert(row: ChannelJira): Int; fun findById(sourceId: Long): ChannelJira? }
