package com.feedback.repository

import com.feedback.model.jira.ChannelJira
import com.feedback.model.channel.ChannelQuality
import com.feedback.model.channel.ChannelRedblack
import com.feedback.mapper.imports.ChannelJiraMapper
import com.feedback.mapper.imports.ChannelQualityMapper
import com.feedback.mapper.imports.ChannelRedblackMapper
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository class ChannelJiraRepository(private val mapper: ChannelJiraMapper) {
    fun save(row: ChannelJira): ChannelJira { mapper.upsert(row); return row }
    fun saveAndFlush(row: ChannelJira) = save(row)
    fun findById(id: Long): Optional<ChannelJira> = Optional.ofNullable(mapper.findById(id))
}
@Repository class ChannelQualityRepository(private val mapper: ChannelQualityMapper) {
    fun save(row: ChannelQuality): ChannelQuality { mapper.upsert(row); return row }
}
@Repository class ChannelRedblackRepository(private val mapper: ChannelRedblackMapper) {
    fun save(row: ChannelRedblack): ChannelRedblack { mapper.upsert(row); return row }
}
