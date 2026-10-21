package com.feedback.mapper.imports

import com.feedback.model.channel.ChannelRedblack
import org.apache.ibatis.annotations.Mapper

@Mapper interface ChannelRedblackMapper { fun upsert(row: ChannelRedblack): Int }
