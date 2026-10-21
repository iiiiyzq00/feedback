package com.feedback.mapper.station.online

import com.feedback.model.po.station.online.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** Station 双写任务表 Mapper；维护幂等创建、租约领取及两分支条件状态推进。 */
@Mapper
interface StationDualWriteTaskMapper {
    /** 插入并在唯一键竞争后定位权威双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun insertOrLocate(row: StationDualWriteTaskPo): Int
    /** 按受控条件查询双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findByIdempotencyKey(@Param("idempotencyKey") idempotencyKey: String): StationDualWriteTaskPo?
    /** 按稳定顺序锁定可领取的双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findClaimableForUpdate(): StationDualWriteTaskPo?
    /** 以当前版本和租约条件领取记录；竞争失败返回未领取，不覆盖其他 Worker 的租约。 */
    fun claim(@Param("id") id: Long, @Param("leaseOwner") leaseOwner: String): Int
    /** 按受控条件查询双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findClaimed(@Param("id") id: Long, @Param("leaseOwner") leaseOwner: String): StationDualWriteTaskPo?
    /** 按前置状态条件更新双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markOldSucceeded(@Param("id") id: Long, @Param("leaseOwner") leaseOwner: String): Int
    /** 按当前状态或租约条件完成双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markCompleted(@Param("id") id: Long, @Param("leaseOwner") leaseOwner: String, @Param("sourceId") sourceId: Long): Int
    /** 按当前状态或租约条件记录失败的双写任务；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun markFailure(@Param("id") id: Long, @Param("leaseOwner") leaseOwner: String,
                    @Param("overallStatus") overallStatus: String, @Param("retryCount") retryCount: Int,
                    @Param("failedStatus") failedStatus: String, @Param("finalFailure") finalFailure: Boolean,
                    @Param("delaySeconds") delaySeconds: Long, @Param("errorCode") errorCode: String,
                    @Param("errorSummary") errorSummary: String): Int
}
