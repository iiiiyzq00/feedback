package com.feedback.manager.applicationidentity

import com.feedback.model.applicationidentity.ActiveApplicationAlias
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** 影子读取组件；两类读取各自只持有短只读事务。 */
@Component
class ApplicationIdentityShadowSnapshotReader(
    private val sources: ApplicationIdentitySourceRepository,
    private val identities: ApplicationIdentityRepository
) {
    /** 来源主表与渠道从表的相关查询共享一个只读快照，返回后立即释放连接。 */
    @Transactional(readOnly = true)
    fun loadInputs(afterSourceId: Long, limit: Int): List<ApplicationIdentitySourceInput> =
        sources.nextInputs(afterSourceId, limit)

    /** alias 与 identity 两次批量查询共享短只读快照；规范化已在事务外完成。 */
    @Transactional(readOnly = true)
    fun loadAliases(keys: List<ApplicationAliasKey>): Map<ApplicationAliasKey, ActiveApplicationAlias> =
        identities.activeAliases(keys)
}
