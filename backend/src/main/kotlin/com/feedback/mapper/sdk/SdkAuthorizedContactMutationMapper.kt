package com.feedback.mapper.sdk

import com.feedback.model.po.sdk.SdkAuthorizedContactPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** SDK 授权联系方式表的参数化单表 Mapper；只读写保留期内联系方式及来源关联。 */
@Mapper
interface SdkAuthorizedContactMutationMapper {
    /** 按受控条件查询受控联系方式；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findRetainedByIngestId(@Param("ingestId") ingestId: Long): List<SdkAuthorizedContactPo>
    /** 按唯一业务键幂等写入受控联系方式；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun upsert(row: SdkAuthorizedContactPo): Int
    /** 建立已校验的关联：受控联系方式；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun linkToSource(@Param("ingestId") ingestId: Long, @Param("sourceId") sourceId: Long): Int
}
