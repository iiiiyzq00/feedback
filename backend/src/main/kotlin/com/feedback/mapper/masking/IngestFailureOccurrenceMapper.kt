package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface IngestFailureOccurrenceMapper { fun insert(row:IngestAlertOccurrencePo):Int }

