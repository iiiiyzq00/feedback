package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 附件元数据 Mapper；按来源身份和摘要去重并支持条件认领。 */
@Mapper
interface StationOnlineAttachmentMapper {
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIdentity(@Param("sourceAttachmentId") sourceAttachmentId: String): StationOnlineAttachmentPo?
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIdentities(@Param("sourceAttachmentIds") sourceAttachmentIds: List<String>): List<StationOnlineAttachmentPo>
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceAndHash(@Param("sourceId") sourceId: Long, @Param("fileSha256") fileSha256: ByteArray): StationOnlineAttachmentPo?
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceAndHashes(@Param("sourceId") sourceId: Long, @Param("fileSha256Values") fileSha256Values: List<ByteArray>): List<StationOnlineAttachmentPo>
    /** 插入并在唯一键竞争后定位权威附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertOrLocate(row: StationOnlineAttachmentPo): Int
    /** 批量插入或定位唯一键对应的附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertOrLocateBatch(@Param("rows") rows: List<StationOnlineAttachmentPo>): Int
    /** 以条件更新认领附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun adoptSourceIdentity(@Param("id") id: Long, @Param("sourceAttachmentId") sourceAttachmentId: String): Int
    /** 以条件更新认领附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun adoptSourceIdentities(@Param("rows") rows: List<StationAttachmentIdentityAdoptionPo>): Int
}
