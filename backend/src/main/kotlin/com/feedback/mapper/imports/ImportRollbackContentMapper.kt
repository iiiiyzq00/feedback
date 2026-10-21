package com.feedback.mapper.imports

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportRollbackContentMapper {
    fun findEligibleSourceIds(@Param("sourceIds") sourceIds: List<Long>): List<Long>
    fun softDeleteBySourceIds(@Param("sourceIds") sourceIds: List<Long>): Int
}
