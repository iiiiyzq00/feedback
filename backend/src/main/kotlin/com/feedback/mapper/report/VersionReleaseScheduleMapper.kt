package com.feedback.mapper.report

import com.feedback.model.query.report.*
import com.feedback.model.po.report.*
import org.apache.ibatis.annotations.Mapper
import java.time.LocalDate

@Mapper interface VersionReleaseScheduleMapper{fun due():List<VersionReleaseSchedulePo>}

