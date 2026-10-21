package com.feedback.mapper.auth

import com.feedback.model.po.auth.AuthFeishuSyncRunPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 飞书组织同步运行记录 Mapper；命名锁与运行状态共同阻止并发启动。 */
@Mapper
interface AuthFeishuSyncRunMapper {
    /** 返回最近同步运行供状态接口展示，不触发新任务。 */
    fun findLatest(): AuthFeishuSyncRunPo?

    /** 以固定超时获取连接级 MySQL 命名锁，1 表示成功。 */
    fun acquireStartLock(
        @Param("lockName") lockName: String,
        @Param("timeoutSeconds") timeoutSeconds: Int,
    ): Int?

    /** 释放当前连接持有的启动命名锁；事务完成回调必须调用。 */
    fun releaseStartLock(@Param("lockName") lockName: String): Int?

    /** 返回 RUNNING 记录供启动事务判断新鲜运行或清理陈旧运行。 */
    fun findRunning(): List<AuthFeishuSyncRunPo>

    /** 创建同步运行并回填主键，必须在已取得启动互斥后调用。 */
    fun insert(run: AuthFeishuSyncRunPo): Int

    /** 将指定陈旧运行条件更新为失败，0 表示并发状态已变化。 */
    fun expireRunning(
        @Param("id") id: Long,
        @Param("errorCode") errorCode: String,
    ): Int

    /** 写入运行终态及汇总计数；影响行数异常由仓储失败关闭。 */
    fun finish(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("total") total: Int,
        @Param("changed") changed: Int,
        @Param("disabled") disabled: Int,
        @Param("failed") failed: Int,
        @Param("errorCode") errorCode: String?,
    ): Int
}
