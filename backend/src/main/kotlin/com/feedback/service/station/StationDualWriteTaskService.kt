package com.feedback.service.station

import com.feedback.model.station.StationDualWriteAcceptResult
import com.feedback.model.station.StationDualWriteEnvelope
import com.feedback.model.station.StationDualWriteTask

/** Station 双写任务 Service；提供幂等受理、租约认领和条件状态推进。 */
interface StationDualWriteTaskService {
    /** 加密保存双写命令，同幂等键异指纹必须冲突。 */
    fun accept(envelope: StationDualWriteEnvelope): StationDualWriteAcceptResult

    /** 短事务认领最多一条任务，返回后不再持有数据库事务。 */
    fun claimOne(workerId: String): StationDualWriteTask?

    /** 标记旧平台分支成功。 */
    fun markOldSucceeded(id: Long, workerId: String)

    /** 标记两端完成并关联新平台 source。 */
    fun markCompleted(id: Long, workerId: String, sourceId: Long)

    /** 按可重试属性、次数上限和指数退避推进失败状态。 */
    fun markFailure(task: StationDualWriteTask, workerId: String, error: Throwable)
}
