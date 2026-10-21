package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 身份表 Mapper；依靠唯一约束实现身份首次写入语义。 */
@Mapper
interface StationOnlineIdentityMapper {
    /** 插入已校验的来源身份；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertIgnore(row: StationOnlineIdentityPo): Int
}
