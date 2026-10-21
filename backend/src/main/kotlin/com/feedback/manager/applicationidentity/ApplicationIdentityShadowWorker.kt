package com.feedback.manager.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.model.applicationidentity.ActiveApplicationAlias
import com.feedback.model.applicationidentity.ApplicationAliasInput
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationIdentitySourceInput
import com.feedback.repository.applicationidentity.ApplicationIdentitySourceRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/** 影子批次编排 Worker；连通分量与批量组装在数据库事务外执行。 */
@Component
class ApplicationIdentityShadowWorker @Autowired constructor(
    private val reader: ApplicationIdentityShadowSnapshotReader,
    private val resolver: ApplicationIdentityResolver,
    private val commitWorker: ApplicationIdentityShadowCommitWorker
) {
    /** 单元测试兼容构造；生产注入始终使用三个已代理组件的主构造。 */
    constructor(
        sources: ApplicationIdentitySourceRepository,
        identities: ApplicationIdentityRepository,
        facts: com.feedback.repository.applicationidentity.PrimaryProblemFactRepository,
        resolver: ApplicationIdentityResolver,
        audits: com.feedback.audit.BusinessAuditService
    ) : this(
        ApplicationIdentityShadowSnapshotReader(sources, identities),
        resolver,
        ApplicationIdentityShadowCommitWorker(identities, facts, audits)
    )
    /** 两段短只读快照之间完成规范化，随后事务外计算连通分量，再进入短写提交。 */
    fun process(
        afterSourceId: Long,
        limit: Int,
        ruleVersion: String,
        actor: Long,
        requestId: String
    ): ApplicationIdentityShadowBatchResult {
        val inputs = reader.loadInputs(afterSourceId, limit)
        if (inputs.isEmpty()) {
            return commitWorker.commitEmpty(afterSourceId, limit, ruleVersion, actor, requestId)
        }
        val keysBySource = inputs.associate { input ->
            input.sourceId to input.aliases.mapNotNull(ApplicationAliasInput::normalized).distinct()
        }
        val allKeys = keysBySource.values.flatten().distinct()
        val activeAliases = reader.loadAliases(allKeys)
        val preliminary = inputs.associate { input ->
            val inputKeys = keysBySource.getValue(input.sourceId).toSet()
            input.sourceId to resolver.resolve(input, activeAliases.filterKeys { it in inputKeys })
        }
        val components = components(inputs, preliminary.mapValues { it.value.keys })
        val plan = ApplicationIdentityShadowPlan(
            inputs,
            components,
            keysBySource,
            activeAliases,
            afterSourceId,
            limit,
            ruleVersion,
            actor,
            requestId
        )
        return commitWorker.commit(plan)
    }

    /** 使用并查集在事务外构建同批 alias 连通分量，避免数据库连接承载 O(n²) 计算。 */
    private fun components(
        inputs: List<ApplicationIdentitySourceInput>,
        keysBySource: Map<Long, List<ApplicationAliasKey>>
    ): List<List<ApplicationIdentitySourceInput>> {
        val parent = IntArray(inputs.size) { it }

        /** 路径压缩只修改本批内存，不接触数据库或外部资源。 */
        fun root(value: Int): Int {
            var current = value
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        /** 以较小根作为稳定代表，确保批次乱序不会改变连通分量输出。 */
        fun union(left: Int, right: Int) {
            val leftRoot = root(left)
            val rightRoot = root(right)
            if (leftRoot != rightRoot) {
                parent[maxOf(leftRoot, rightRoot)] = minOf(leftRoot, rightRoot)
            }
        }

        val firstByKey = mutableMapOf<ApplicationAliasKey, Int>()
        inputs.forEachIndexed { index, input ->
            keysBySource.getValue(input.sourceId).forEach { key ->
                firstByKey.putIfAbsent(key, index)?.let { previous -> union(previous, index) }
            }
        }
        return inputs.indices.groupBy(::root).toSortedMap().values.map { indexes -> indexes.map(inputs::get) }
    }
}

/** 验证 alias 快照仍可提交；任一绑定或 identity 版本变化都要求调用方重跑整个安全阶段。 */
internal fun requireUnchangedAliasSnapshot(
    expected: Map<ApplicationAliasKey, ActiveApplicationAlias>,
    current: Map<ApplicationAliasKey, ActiveApplicationAlias>
) {
    val expectedVersions = expected.mapValues { (_, alias) ->
        Triple(alias.publicId, alias.aliasVersion, alias.identity.id to alias.identity.mappingVersion)
    }
    val currentVersions = current.mapValues { (_, alias) ->
        Triple(alias.publicId, alias.aliasVersion, alias.identity.id to alias.identity.mappingVersion)
    }
    if (expectedVersions != currentVersions) {
        throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, "APPLICATION_SHADOW_SNAPSHOT_CONFLICT")
    }
}
