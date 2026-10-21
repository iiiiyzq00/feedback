package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.ApplicationIdentitySafeTextPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_feedback_content 的安全文本单表只读 Mapper，不属于 identity 写边界。 */
@Mapper
interface ApplicationIdentitySafeTextMapper {
    /** 只读取 masking PASS 且未删除的有界来源文本。 */
    fun findPassingBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ApplicationIdentitySafeTextPo>
}
