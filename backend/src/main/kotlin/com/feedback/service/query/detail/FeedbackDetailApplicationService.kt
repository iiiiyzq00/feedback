package com.feedback.service.query.detail

import com.feedback.application.port.feedback.detail.*
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.FeedbackAccessGuard
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 详情用例共用的授权快照入口。访问守卫始终先于字段策略及任何详情辅助事实读取。
 * 本组件只固定当前调用的安全上下文，不读取详情数据。
 */
@Component
class FeedbackDetailAccessContextProvider(
    private val accessGuard: FeedbackAccessGuard,
    private val permissions: DataPermissionService,
    private val principals: CurrentWebPrincipalProvider
) {
    fun authorize(sourceId: Long): AuthorizedFeedbackAccess {
        val access = accessGuard.require(sourceId)
        return AuthorizedFeedbackAccess(scope = access.scope, principal = principals.require())
    }

    fun authorizeWithFieldPolicy(sourceId: Long): AuthorizedFeedbackRead {
        val access = accessGuard.require(sourceId)
        return AuthorizedFeedbackRead(
            scope = access.scope,
            principal = principals.require(),
            fieldPolicy = permissions.fieldPolicy()
        )
    }
}

/**
 * 在同一 REPEATABLE_READ 只读事务中完成授权、字段策略和详情事实读取，关闭授权事实与详情事实之间的
 * TOCTOU 窗口。事务返回后不再持有连接，写审计由用例调用另一个 Bean。
 */
@Component
class AuthorizedFeedbackDetailSnapshotReader(
    private val access: FeedbackDetailAccessContextProvider,
    private val reads: FeedbackDetailReadPort,
    private val externalLinks: FeedbackExternalLinkResolver
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(sourceId: Long): AuthorizedDetailSnapshot {
        val authorization = access.authorizeWithFieldPolicy(sourceId)
        val detail = reads.read(sourceId, authorization.scope)
        return AuthorizedDetailSnapshot(
            authorization = authorization,
            detail = detail,
            externalLinkAvailable = externalLinks.available(detail.externalLinkFacts, authorization.principal)
        )
    }
}

/** 在同一只读一致性快照中完成访问校验、字段策略和字段历史读取。 */
@Component
class AuthorizedFeedbackModificationSnapshotReader(
    private val access: FeedbackDetailAccessContextProvider,
    private val reads: FeedbackModificationReadPort
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(sourceId: Long): AuthorizedModificationSnapshot {
        val authorization = access.authorizeWithFieldPolicy(sourceId)
        return AuthorizedModificationSnapshot(authorization, reads.readModifications(sourceId))
    }
}

/** 在同一只读一致性快照中完成访问校验、字段策略和时间轴事实读取。 */
@Component
class AuthorizedFeedbackTimelineSnapshotReader(
    private val access: FeedbackDetailAccessContextProvider,
    private val reads: FeedbackTimelineReadPort
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(sourceId: Long): AuthorizedTimelineSnapshot {
        val authorization = access.authorizeWithFieldPolicy(sourceId)
        return AuthorizedTimelineSnapshot(authorization, reads.readTimeline(sourceId))
    }
}

/** 在同一只读一致性快照中按“守卫、来源、角色、渠道、配置”的顺序生成安全外链快照。 */
@Component
class AuthorizedFeedbackExternalLinkSnapshotReader(
    private val access: FeedbackDetailAccessContextProvider,
    private val reads: FeedbackExternalLinkReadPort,
    private val externalLinks: FeedbackExternalLinkResolver
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(sourceId: Long): AuthorizedExternalLinkSnapshot {
        val authorization = access.authorize(sourceId)
        val source = reads.source(sourceId)
        if (!externalLinks.mayOpen(source.channel, authorization.principal)) {
            throw ClientException(ErrorCode.AUTH_FORBIDDEN, "EXTERNAL_LINK_FORBIDDEN")
        }
        val resolved = externalLinks.require(reads.channel(sourceId, source.channel))
        return AuthorizedExternalLinkSnapshot(
            principal = authorization.principal,
            sourceId = sourceId,
            channel = source.channel,
            url = resolved.url,
            host = resolved.host
        )
    }
}

/** 聚合详情用例；先取得只读快照，随后在独立 Bean 的写事务中完成审计，成功后才返回响应。 */
@Service
class FeedbackDetailQueryService(
    private val snapshots: AuthorizedFeedbackDetailSnapshotReader,
    private val assembler: FeedbackDetailAssembler,
    private val audits: FeedbackDetailAuditPort
) : FeedbackDetailQueryUseCase {
    override fun detail(sourceId: Long): FeedbackDetailResponse {
        val authorized = snapshots.read(sourceId)
        val response = assembler.detail(
            snapshot = authorized.detail,
            externalLinkAvailable = authorized.externalLinkAvailable,
            policy = authorized.authorization.fieldPolicy
        )
        audits.append(
            FeedbackDetailAuditEvent.DetailViewed(
                userId = authorized.authorization.principal.userId,
                sourceId = sourceId,
                channel = authorized.detail.source.sourceChannel.lowercase()
            )
        )
        return response
    }
}

/** 字段修改历史纯读用例；按历史声明的真实业务字段应用同一次字段策略快照。 */
@Service
class FeedbackModificationQueryService(
    private val snapshots: AuthorizedFeedbackModificationSnapshotReader
) : FeedbackModificationQueryUseCase {
    override fun modifications(sourceId: Long): List<FeedbackFieldHistoryResponse> {
        val snapshot = snapshots.read(sourceId)
        return filterHistory(snapshot.modifications, snapshot.authorization.fieldPolicy)
    }
}

/** 时间轴纯读用例；各事实由只读端口一次性返回，再在内存中保持既有排序。 */
@Service
class FeedbackTimelineQueryService(
    private val snapshots: AuthorizedFeedbackTimelineSnapshotReader,
    private val assembler: FeedbackTimelineAssembler
) : FeedbackTimelineQueryUseCase {
    override fun timeline(sourceId: Long): List<FeedbackTimelineEventResponse> {
        val authorized = snapshots.read(sourceId)
        val snapshot = authorized.timeline
        return assembler.assemble(
            FeedbackTimelineFacts(
                source = snapshot.source,
                content = snapshot.content,
                ai = snapshot.ai,
                importBatch = snapshot.importBatch,
                importRow = snapshot.importRow,
                clusterLinks = snapshot.clusterLinks,
                fieldHistory = filterHistory(snapshot.fieldHistory, authorized.authorization.fieldPolicy),
                fieldPolicy = authorized.authorization.fieldPolicy
            )
        )
    }
}

/** 外链用例；角色检查先于渠道配置读取，URI 校验和审计任一失败均不返回地址。 */
@Service
class FeedbackExternalLinkQueryService(
    private val snapshots: AuthorizedFeedbackExternalLinkSnapshotReader,
    private val audits: FeedbackDetailAuditPort
) : FeedbackExternalLinkQueryUseCase {
    override fun externalLink(sourceId: Long): FeedbackExternalLinkResponse {
        val snapshot = snapshots.read(sourceId)
        audits.append(
            FeedbackDetailAuditEvent.ExternalLinkViewed(
                userId = snapshot.principal.userId,
                sourceId = sourceId,
                channel = snapshot.channel,
                host = snapshot.host
            )
        )
        return FeedbackExternalLinkResponse(url = snapshot.url, channel = snapshot.channel)
    }
}

/** 缺字段名或实际业务字段被拒绝时整条历史记录失败关闭。 */
internal fun filterHistory(
    facts: List<FeedbackFieldModificationFact>,
    policy: DataFieldPolicy
): List<FeedbackFieldHistoryResponse> = facts.mapNotNull { fact ->
    val fieldName = fact.fieldName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
    if (!policy.permits(fieldName)) return@mapNotNull null
    FeedbackFieldHistoryResponse(
        id = fact.id,
        fieldName = fieldName,
        oldValue = fact.oldValue,
        newValue = fact.newValue,
        changeType = fact.changeType,
        changedByType = fact.changedByType,
        changedByUserId = fact.userId,
        mappingVersion = fact.mappingVersion,
        normalizationVersion = fact.normalizationVersion,
        changeReason = fact.changeReason,
        createdAt = fact.createdAt,
        operator = fact.operator,
        fieldPolicy = policy
    )
}
