package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station Online 渠道表 Mapper；承载运维查询、租约领取续租及 owner 条件状态推进。 */
@Mapper
interface StationOnlineChannelMapper {
    /** 插入已校验的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insert(row: StationOnlineChannelPo): Int
    /** 按受控条件查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceId(@Param("sourceId") sourceId: Long): StationOnlineChannelPo?
    /** 按受控条件查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<StationOnlineChannelPo>
    /** 统计受控范围内的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun countOperations(@Param("status") status: String?): Long
    /** 按受控条件查询渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findOperationsPage(@Param("status") status: String?, @Param("offset") offset: Long, @Param("limit") limit: Int): List<StationOperationsTaskPo>
    /** 汇总受控范围内的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun summarizeOperations(): List<StationOperationsStatusCountPo>
    /** 锁定符合条件的可重试渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findRetryableByReceiptsForUpdate(@Param("receiptIds") receiptIds: List<ByteArray>): List<StationOnlineChannelPo>
    /** 按稳定顺序锁定可领取的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findClaimableForUpdate(): StationOnlineChannelPo?
    /** 以当前版本和租约条件领取记录；竞争失败返回未领取，不覆盖其他 Worker 的租约。 */
    fun claim(@Param("sourceId") sourceId: Long, @Param("leaseOwner") leaseOwner: String): Int
    /** 仅允许当前 lease owner 延长未过期租约；影响行数异常按租约丢失处理。 */
    fun renewLease(@Param("sourceId") sourceId: Long, @Param("leaseOwner") leaseOwner: String): Int
    /** 按当前状态或租约条件完成渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun complete(@Param("sourceId") sourceId: Long, @Param("leaseOwner") leaseOwner: String,
                 @Param("unavailableAttachments") unavailableAttachments: Int,
                 @Param("identityStored") identityStored: Boolean): Int
    /** 按当前状态或租约条件记录失败的渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun fail(@Param("sourceId") sourceId: Long, @Param("leaseOwner") leaseOwner: String,
             @Param("status") status: String, @Param("retryCount") retryCount: Int,
             @Param("delaySeconds") delaySeconds: Long, @Param("review") review: Boolean,
             @Param("errorCode") errorCode: String, @Param("errorSummary") errorSummary: String): Int
    /** 按前置状态条件更新渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markContactUnavailable(@Param("sourceId") sourceId: Long, @Param("leaseOwner") leaseOwner: String): Int
    /** 按条件重置可重试渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun resetRetryable(@Param("sourceIds") sourceIds: List<Long>): Int
    /** 按前置状态条件更新渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markMaskingRecovered(@Param("sourceId") sourceId: Long): Int
    /** 按前置状态条件更新渠道状态；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markMaskingFailure(@Param("sourceId") sourceId: Long, @Param("errorSummary") errorSummary: String): Int
}
