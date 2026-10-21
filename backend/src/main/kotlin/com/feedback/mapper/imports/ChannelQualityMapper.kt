package com.feedback.mapper.imports

import com.feedback.model.channel.ChannelQuality
import org.apache.ibatis.annotations.Mapper

@Mapper interface ChannelQualityMapper { fun upsert(row: ChannelQuality): Int }
