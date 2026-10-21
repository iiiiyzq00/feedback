package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import java.time.LocalDateTime

@Mapper interface AlertLoopInstanceMapper{fun runtime():AlertInstanceRuntimePo}

