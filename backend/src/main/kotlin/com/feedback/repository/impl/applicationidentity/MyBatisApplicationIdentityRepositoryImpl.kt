package com.feedback.repository.impl.applicationidentity

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.applicationidentity.ApplicationIdentityAliasMapper
import com.feedback.mapper.applicationidentity.ApplicationIdentityMapper
import com.feedback.model.po.applicationidentity.ApplicationIdentityAliasPo
import com.feedback.model.po.applicationidentity.ApplicationIdentityPo
import com.feedback.model.po.applicationidentity.ApplicationIdentityVersionCasPo
import com.feedback.model.applicationidentity.ActiveApplicationAlias
import com.feedback.model.applicationidentity.ApplicationAliasBindingStatus
import com.feedback.model.applicationidentity.ApplicationAliasEvidenceType
import com.feedback.model.applicationidentity.ApplicationAliasKey
import com.feedback.model.applicationidentity.ApplicationAliasType
import com.feedback.model.applicationidentity.ApplicationIdentityAliasSnapshot
import com.feedback.model.applicationidentity.ApplicationIdentityLifecycleStatus
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import com.feedback.model.applicationidentity.ApplicationSourceChannel
import com.feedback.model.applicationidentity.NewApplicationIdentity
import com.feedback.model.applicationidentity.NewApplicationIdentityAlias
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** identity/alias 的 MyBatis 聚合实现；PO 与稳定字符串不会泄漏到上层契约。 */
@Repository
class MyBatisApplicationIdentityRepositoryImpl(
    private val identityMapper: ApplicationIdentityMapper,
    private val aliasMapper: ApplicationIdentityAliasMapper
) : ApplicationIdentityRepository {
    /** 以有界哈希集合批量读取 alias，再批量读取 identity，避免逐 alias 的 N+1。 */
    override fun activeAliases(keys: Collection<ApplicationAliasKey>): Map<ApplicationAliasKey, ActiveApplicationAlias> {
        val bounded = boundedKeys(keys)
        if (bounded.isEmpty()) {
            return emptyMap()
        }
        val rows = aliasMapper.findActiveByKeyHexes(bounded.map(::activeKeyHex))
        if (rows.size != rows.distinctBy { it.id }.size) {
            sourceIntegrity("APPLICATION_ALIAS_DUPLICATED")
        }
        if (rows.isEmpty()) {
            return emptyMap()
        }
        val identities = identityMapper.findByIds(rows.map { it.applicationIdentityId }.distinct()).associateByRequiredId()
        return rows.associate { row ->
            val key = row.key()
            if (key !in bounded) {
                sourceIntegrity("APPLICATION_ALIAS_UNEXPECTED_KEY")
            }
            if (row.activeAliasKeyHex != activeKeyHex(key)) {
                sourceIntegrity("APPLICATION_ALIAS_HASH_COLLISION")
            }
            val identity = identities[row.applicationIdentityId] ?: sourceIntegrity("APPLICATION_IDENTITY_MISSING")
            key to ActiveApplicationAlias(row.aliasPublicId, key, row.aliasVersion, identity.snapshot())
        }
    }

    /** 在调用方短写事务内批量插入并回读生成 ID，结果必须完整覆盖请求 UUID。 */
    override fun createIdentities(rows: List<NewApplicationIdentity>): Map<String, ApplicationIdentitySnapshot> {
        if (rows.isEmpty()) {
            return emptyMap()
        }
        if (rows.size > ApplicationIdentityRepository.MAX_BATCH_SIZE) {
            invalidInput("APPLICATION_IDENTITY_BATCH_TOO_LARGE")
        }
        if (rows.map { it.publicId }.distinct().size != rows.size) {
            invalidInput("APPLICATION_IDENTITY_PUBLIC_ID_DUPLICATED")
        }
        val persistedRows = rows.map {
            ApplicationIdentityPo(
                publicId = it.publicId,
                displayName = it.displayName,
                lifecycleStatus = ApplicationIdentityLifecycleStatus.ACTIVE.code,
                createdBy = it.createdBy
            )
        }
        if (identityMapper.insertAll(persistedRows) != rows.size) {
            persistenceFailure("APPLICATION_IDENTITY_INSERT_COUNT_INVALID")
        }
        val persisted = identityMapper.findByPublicIds(rows.map { it.publicId })
        if (persisted.size != rows.size) {
            sourceIntegrity("APPLICATION_IDENTITY_INSERT_RESULT_MISSING")
        }
        return persisted.associate { it.publicId to it.snapshot() }
    }

    /** 将领域 alias 批量转换为 PO；活动键唯一约束拒绝并发双绑定。 */
    override fun createAutoAliases(bindings: Map<ApplicationAliasKey, ApplicationIdentitySnapshot>) {
        if (bindings.isEmpty()) {
            return
        }
        if (bindings.size > ApplicationIdentityRepository.MAX_ALIAS_BATCH_SIZE) {
            invalidInput("APPLICATION_ALIAS_BATCH_TOO_LARGE")
        }
        val rows = bindings.map { (key, identity) ->
            ApplicationIdentityAliasPo(
                aliasPublicId = UUID.randomUUID().toString(),
                sourceChannel = key.sourceChannel.code,
                aliasType = key.aliasType.code,
                normalizedAlias = key.normalizedAlias,
                activeAliasKeyHex = activeKeyHex(key),
                applicationIdentityId = identity.id,
                bindingStatus = ApplicationAliasBindingStatus.ACTIVE.code,
                evidenceType = ApplicationAliasEvidenceType.AUTO_SAME_SOURCE.code
            )
        }
        if (aliasMapper.insertAll(rows) != rows.size) {
            persistenceFailure("APPLICATION_ALIAS_INSERT_COUNT_INVALID")
        }
    }

    /** 单条查询不额外开启事务；数据库字符串在此严格转换。 */
    override fun findByPublicId(publicId: String): ApplicationIdentitySnapshot? =
        identityMapper.findByPublicId(publicId)?.snapshot()

    /** 聚合内部按主键读取，不向上层暴露 PO。 */
    override fun findById(id: Long): ApplicationIdentitySnapshot? = identityMapper.findById(id)?.snapshot()

    /** 最多读取两行即可识别规范展示名冲突。 */
    override fun activeByDisplayName(displayName: String): List<ApplicationIdentitySnapshot> =
        identityMapper.findActiveByDisplayName(displayName, 2).map { it.snapshot() }

    /** 批量审核目标查询最多接收 20 个互异名称，并多取一行探测重复数据越界。 */
    override fun activeByDisplayNames(
        displayNames: Collection<String>
    ): Map<String, List<ApplicationIdentitySnapshot>> {
        val names = displayNames.map(String::trim).distinct().sorted()
        if (names.isEmpty()) {
            return emptyMap()
        }
        if (names.size > MAX_REVIEW_TARGETS || names.any { it.isEmpty() || it.length > 200 }) {
            invalidInput("APPLICATION_REVIEW_TARGET_SCOPE_INVALID")
        }
        val rows = identityMapper.findActiveByDisplayNames(names, names.size * 2 + 1)
        if (rows.size > names.size * 2) {
            sourceIntegrity("APPLICATION_REVIEW_TARGET_SCOPE_EXCEEDED")
        }
        if (rows.any { it.displayName !in names }) {
            sourceIntegrity("APPLICATION_REVIEW_TARGET_UNEXPECTED")
        }
        return rows.groupBy(ApplicationIdentityPo::displayName)
            .mapValues { (_, values) -> values.map { it.snapshot() } }
    }

    /** UUID 游标与页大小在访问 Mapper 前校验，杜绝无界列表查询。 */
    override fun listAfter(afterPublicId: String?, limit: Int): List<ApplicationIdentitySnapshot> {
        if (limit !in 1..ApplicationIdentityRepository.MAX_PAGE_SIZE) {
            invalidInput("APPLICATION_IDENTITY_PAGE_SIZE_INVALID")
        }
        afterPublicId?.let(::requireUuid)
        return identityMapper.findAfterPublicId(afterPublicId, limit).map { it.snapshot() }
    }

    /** alias 历史读取多取一行探测越界，绝不静默截断。 */
    override fun aliasesFor(identityId: Long): List<ApplicationIdentityAliasSnapshot> =
        aliasMapper.findByIdentityId(identityId, ApplicationIdentityRepository.MAX_ALIAS_HISTORY + 1).also {
            if (it.size > ApplicationIdentityRepository.MAX_ALIAS_HISTORY) {
                sourceIntegrity("APPLICATION_ALIAS_HISTORY_LIMIT_EXCEEDED")
            }
        }.map { it.snapshot() }

    /** 锁前只读幂等快照用于快速重放，写事务仍必须锁后复核。 */
    override fun findManualResult(requestId: String): ApplicationIdentityAliasSnapshot? =
        aliasMapper.findByRequestId(requestId)?.snapshot()

    /** requestId 当前读必须在写事务内执行，防止锁前快照误判。 */
    override fun lockManualResult(requestId: String): ApplicationIdentityAliasSnapshot? =
        aliasMapper.lockByRequestId(requestId)?.snapshot()

    /** 人工重绑的第一把锁固定为活动 alias 行。 */
    override fun lockActiveAlias(aliasPublicId: String): ApplicationIdentityAliasSnapshot? =
        aliasMapper.lockActiveByPublicId(aliasPublicId)?.snapshot()

    /** 逐个按角色顺序加锁，调用方固定传 source identity 后 target identity。 */
    override fun lockIdentitiesInOrder(ids: List<Long>): Map<Long, ApplicationIdentitySnapshot> {
        val bounded = ids.distinct()
        if (bounded.isEmpty() || bounded.size > 2) {
            invalidInput("APPLICATION_IDENTITY_LOCK_SCOPE_INVALID")
        }
        val result = linkedMapOf<Long, ApplicationIdentitySnapshot>()
        bounded.forEach { id ->
            identityMapper.lockById(id)?.let { row -> result[id] = row.snapshot() }
        }
        return result
    }

    /** 单 identity CAS 是一条原子 SQL，不在 RepositoryImpl 额外扩大事务。 */
    override fun incrementVersion(identityId: Long, expectedVersion: Long) {
        if (identityMapper.compareAndIncrementVersion(identityId, expectedVersion) != 1) {
            conflict("APPLICATION_IDENTITY_VERSION_CONFLICT")
        }
    }

    /** 双/批量版本推进使用一条条件 UPDATE；受影响数不完整则整个上层事务回滚。 */
    override fun incrementVersions(
        identities: Collection<ApplicationIdentitySnapshot>
    ): Map<Long, ApplicationIdentitySnapshot> {
        val unique = identities.distinctBy { it.id }
        if (unique.isEmpty()) {
            return emptyMap()
        }
        if (unique.size > ApplicationIdentityRepository.MAX_BATCH_SIZE) {
            invalidInput("APPLICATION_IDENTITY_VERSION_BATCH_TOO_LARGE")
        }
        val rows = unique.map { ApplicationIdentityVersionCasPo(it.id, it.mappingVersion) }
        if (identityMapper.compareAndIncrementVersions(rows) != rows.size) {
            conflict("APPLICATION_IDENTITY_VERSION_CONFLICT")
        }
        return unique.associate { identity -> identity.id to identity.copy(mappingVersion = identity.mappingVersion + 1) }
    }

    /** alias 旧版本失效必须与双 identity CAS、新版本和审计处于同一短写事务。 */
    override fun supersedeAlias(row: ApplicationIdentityAliasSnapshot) {
        val id = row.id ?: sourceIntegrity("APPLICATION_ALIAS_ID_MISSING")
        if (aliasMapper.supersede(id, row.aliasVersion) != 1) {
            conflict("APPLICATION_ALIAS_VERSION_CONFLICT")
        }
    }

    /** 将强类型人工 alias 转为稳定数据库 code 后插入。 */
    override fun insertManualAlias(row: NewApplicationIdentityAlias) {
        val po = ApplicationIdentityAliasPo(
            aliasPublicId = row.aliasPublicId,
            sourceChannel = row.key.sourceChannel.code,
            aliasType = row.key.aliasType.code,
            normalizedAlias = row.key.normalizedAlias,
            activeAliasKeyHex = row.activeAliasKeyHex,
            applicationIdentityId = row.applicationIdentityId,
            aliasVersion = row.aliasVersion,
            bindingStatus = ApplicationAliasBindingStatus.ACTIVE.code,
            evidenceType = row.evidenceType.code,
            previousBindingId = row.previousBindingId,
            requestId = row.requestId,
            commandFingerprintHex = row.commandFingerprintHex,
            targetMappingVersionSnapshot = row.targetMappingVersionSnapshot,
            changedBy = row.changedBy,
            changeReason = row.changeReason
        )
        if (aliasMapper.insert(po) != 1) {
            persistenceFailure("APPLICATION_ALIAS_REBIND_INSERT_FAILED")
        }
    }

    /** 诊断计数在 Repository 边界严格转换 alias 类型编码。 */
    override fun aliasTypeCounts(): Map<ApplicationAliasType, Long> = aliasMapper.countActiveByType().associate {
        parseAliasType(it.aliasType) to it.itemCount
    }

    /** SHA-256 输入分隔与大写十六进制格式属于唯一键兼容契约。 */
    override fun activeKeyHex(key: ApplicationAliasKey): String = sha256Hex(
        listOf(key.sourceChannel.code, key.aliasType.code, key.normalizedAlias).joinToString("\u001f")
    )

    /** 去重并稳定排序批量键，同时强制显式集合上限。 */
    private fun boundedKeys(values: Collection<ApplicationAliasKey>): List<ApplicationAliasKey> = values.distinct()
        .sortedWith(compareBy({ it.sourceChannel.code }, { it.aliasType.code }, { it.normalizedAlias }))
        .also {
            if (it.size > ApplicationIdentityRepository.MAX_ALIAS_BATCH_SIZE) {
                invalidInput("APPLICATION_ALIAS_BATCH_TOO_LARGE")
            }
        }

    /** 将 identity PO 列表按必填主键索引，并拒绝数据库重复结果。 */
    private fun List<ApplicationIdentityPo>.associateByRequiredId(): Map<Long, ApplicationIdentityPo> {
        val result = associateBy { it.id ?: sourceIntegrity("APPLICATION_IDENTITY_ID_MISSING") }
        if (result.size != size) {
            sourceIntegrity("APPLICATION_IDENTITY_DUPLICATED")
        }
        return result
    }

    /** 将 identity PO 转为强类型快照；非活动记录不会穿透该活动仓储。 */
    private fun ApplicationIdentityPo.snapshot(): ApplicationIdentitySnapshot {
        val requiredId = id ?: sourceIntegrity("APPLICATION_IDENTITY_ID_MISSING")
        val lifecycle = runCatching { ApplicationIdentityLifecycleStatus.fromCode(lifecycleStatus) }
            .getOrElse { sourceIntegrity("APPLICATION_IDENTITY_STATUS_INVALID") }
        if (lifecycle != ApplicationIdentityLifecycleStatus.ACTIVE) {
            sourceIntegrity("APPLICATION_IDENTITY_NOT_ACTIVE")
        }
        return ApplicationIdentitySnapshot(requiredId, publicId, displayName, mappingVersion, lifecycle)
    }

    /** 将 alias PO 的三段键严格转换为领域键。 */
    private fun ApplicationIdentityAliasPo.key(): ApplicationAliasKey = ApplicationAliasKey(
        parseChannel(sourceChannel),
        parseAliasType(aliasType),
        normalizedAlias
    )

    /** 将完整 alias PO 转成历史快照，供上层锁/CAS 编排使用。 */
    private fun ApplicationIdentityAliasPo.snapshot(): ApplicationIdentityAliasSnapshot = ApplicationIdentityAliasSnapshot(
        id,
        aliasPublicId,
        key(),
        activeAliasKeyHex,
        applicationIdentityId,
        aliasVersion,
        runCatching { ApplicationAliasBindingStatus.fromCode(bindingStatus) }
            .getOrElse { sourceIntegrity("APPLICATION_ALIAS_BINDING_STATUS_INVALID") },
        runCatching { ApplicationAliasEvidenceType.fromCode(evidenceType) }
            .getOrElse { sourceIntegrity("APPLICATION_ALIAS_EVIDENCE_TYPE_INVALID") },
        previousBindingId,
        requestId,
        commandFingerprintHex,
        targetMappingVersionSnapshot,
        changedBy,
        changeReason
    )

    /** 严格解析来源渠道编码。 */
    private fun parseChannel(code: String): ApplicationSourceChannel = runCatching {
        ApplicationSourceChannel.fromCode(code)
    }.getOrElse {
        sourceIntegrity("APPLICATION_SOURCE_CHANNEL_INVALID")
    }

    /** 严格解析 alias 类型编码。 */
    private fun parseAliasType(code: String): ApplicationAliasType = runCatching {
        ApplicationAliasType.fromCode(code)
    }.getOrElse {
        sourceIntegrity("APPLICATION_ALIAS_TYPE_INVALID")
    }

    /** 校验稳定 UUID，避免无效参数进入 UUID_TO_BIN。 */
    private fun requireUuid(value: String) {
        runCatching { UUID.fromString(value) }.getOrElse {
            invalidInput("APPLICATION_UUID_INVALID")
        }
    }

    /** 生成稳定 SHA-256 大写十六进制字符串。 */
    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02X".format(it) }

    companion object {
        private const val MAX_REVIEW_TARGETS = 20
    }
}

/** 调用方输入违反有界或格式契约。 */
private fun invalidInput(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)

/** Mapper 返回违反单表成员或状态约束时失败关闭。 */
private fun sourceIntegrity(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED, reasonCode)

/** CAS 或唯一业务状态冲突使用统一 409 语义。 */
private fun conflict(reasonCode: String): Nothing =
    throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reasonCode)

/** 批量写入受影响数不符合约定时返回内部持久化故障。 */
private fun persistenceFailure(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)
