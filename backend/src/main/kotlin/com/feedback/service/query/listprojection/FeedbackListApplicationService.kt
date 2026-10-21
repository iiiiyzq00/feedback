package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListCriteria
import com.feedback.application.port.feedback.list.FeedbackListReadPort
import com.feedback.application.port.feedback.list.FeedbackListRecord
import com.feedback.application.port.feedback.list.FeedbackListStats
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.config.FeedbackListReadProperties
import com.feedback.model.po.feedback.listprojection.FeedbackListProjectionGenerationPo
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.PrincipalAccess
import com.feedback.security.WebPrincipal
import com.feedback.service.query.FeedbackListQuery
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** 在读取开关开启时固定一个验证完整的 ACTIVE generation。 */
@Component
class FeedbackListSnapshotProvider(
    private val properties: FeedbackListReadProperties,
    private val generationProvider: ActiveGenerationProvider
) {
    fun require(): FeedbackListProjectionGenerationPo {
        if (!properties.readEnabled) {
            throw ServiceException(ErrorCode.FEEDBACK_READ_UNAVAILABLE, "FEEDBACK_FEEDBACK_LIST_READ_DISABLED")
        }
        return generationProvider.require(ProjectionCapability.LIST)
    }
}

/** 组装一个列表页；负责人和诊断值均批量读取，保持既有查询次数与顺序。 */
@Component
class FeedbackListPageAssembler(
    private val reads: FeedbackListReadPort,
    private val rowAssembler: FeedbackListAssembler,
    private val diagnosticReader: ListDiagnosticReader
) {
    fun assemble(
        rows: List<FeedbackListRecord>,
        principal: WebPrincipal,
        fieldPolicy: DataFieldPolicy
    ): FeedbackListPageAssembly {
        val owners = reads.owners(rows.mapNotNull(FeedbackListRecord::ownerUserId))
        val diagnostics = diagnosticReader.read(rows, principal)
        val records = rows.map { row ->
            val values = diagnostics.values[row.sourceId]
            rowAssembler.row(
                row,
                row.ownerUserId?.let(owners::get),
                fieldPolicy,
                values?.items.orEmpty(),
                values?.visibility ?: "PARTIALLY_HIDDEN"
            )
        }
        return FeedbackListPageAssembly(records, diagnostics.audits)
    }
}

data class FeedbackListPageAssembly(
    val records: List<FeedbackListRowResponse>,
    val audits: List<ListDiagnosticAudit>
)

data class FeedbackListSnapshot(
    val response: FeedbackListResponse,
    val diagnosticAudits: List<ListDiagnosticAudit>
)

/**
 * 在单个只读一致性事务内固定 generation 并完成列表快照组装。
 *
 * 返回后事务代理已提交并释放连接；本类不写诊断审计，避免嵌套事务额外占用连接池连接。
 */
@Component
class FeedbackListSnapshotReader(
    private val reads: FeedbackListReadPort,
    private val criteriaFactory: FeedbackListCriteriaFactory,
    private val snapshotProvider: FeedbackListSnapshotProvider,
    private val pageAssembler: FeedbackListPageAssembler
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(query: FeedbackListQuery, principal: WebPrincipal): FeedbackListSnapshot {
        val generation = snapshotProvider.require()
        val access = criteriaFactory.create(query, principal)
        val stats = reads.stats(generation.id, access.criteria)
        val rows = if (stats.total == 0L) emptyList() else reads.page(
            generation.id, access.criteria, query.page.size, (query.page.page - 1L) * query.page.size
        )
        val page = pageAssembler.assemble(rows, principal, access.fieldPolicy)
        return FeedbackListSnapshot(
            response = FeedbackListResponse(
                records = page.records,
                total = stats.total,
                page = query.page.page,
                size = query.page.size,
                stats = stats.response()
            ),
            diagnosticAudits = page.audits
        )
    }

    private fun FeedbackListStats.response() = FeedbackListStatsResponse(
        total = total,
        latestTime = latestTime,
        latestUpdateTime = latestUpdateTime,
        latestIngestTime = latestIngestTime,
        channelCount = channelCount,
        unassignedCount = unassignedCount,
        activeCount = activeCount,
        resolvedCount = resolvedCount,
        withEvidenceCount = withEvidenceCount,
        positiveCount = positiveCount,
        negativeCount = negativeCount,
        newRecords5m = newRecords5m
    )
}

/**
 * 反馈列表查询用例。
 *
 * 非事务编排器先完成只读快照，再在连接已释放后写诊断审计；审计成功后才向 Controller 返回响应。
 * 开关关闭或 generation 不完整时失败关闭，不回退旧 SQL。
 */
@Service
class FeedbackListApplicationService(
    private val currentPrincipal: CurrentWebPrincipalProvider,
    private val criteriaFactory: FeedbackListCriteriaFactory,
    private val snapshotReader: FeedbackListSnapshotReader,
    private val diagnosticAuditWriter: ListDiagnosticAuditWriter
) : FeedbackListQueryUseCase {
    override fun list(query: FeedbackListQuery): FeedbackListResponse {
        val principal = PrincipalAccess.requireWorkspace(currentPrincipal.require())
        criteriaFactory.validate(query)
        val snapshot = snapshotReader.read(query, principal)
        if (snapshot.diagnosticAudits.isNotEmpty()) diagnosticAuditWriter.append(snapshot.diagnosticAudits)
        return snapshot.response
    }
}

/** 查询导出和显式 ID 导出用例；审计写入仍与读取保持在原有事务边界内。 */
@Service
class FeedbackExportApplicationService(
    private val reads: FeedbackListReadPort,
    private val currentPrincipal: CurrentWebPrincipalProvider,
    private val criteriaFactory: FeedbackListCriteriaFactory,
    private val snapshotProvider: FeedbackListSnapshotProvider,
    private val csvExporter: FeedbackCsvExporter
) : FeedbackExportUseCase {
    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = [com.feedback.service.masking.OutboundPrivacyException::class])
    override fun exportQuery(query: FeedbackListQuery, auditContext: FeedbackExportAuditContext): String {
        val principal = PrincipalAccess.requireWorkspace(currentPrincipal.require())
        criteriaFactory.validate(query)
        val generation = snapshotProvider.require()
        val access = criteriaFactory.create(query, principal)
        val rows = reads.exportRows(generation.id, access.criteria, EXPORT_SENTINEL)
        if (rows.size >= EXPORT_SENTINEL) throw ClientException(ErrorCode.BAD_REQUEST, "FEEDBACK_EXPORT_LIMIT_EXCEEDED")
        return csvExporter.export(rows, access.fieldPolicy, access.actorId, generation.id, "QUERY", auditContext)
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = [com.feedback.service.masking.OutboundPrivacyException::class])
    override fun exportSelection(ids: List<Long>, auditContext: FeedbackExportAuditContext): String {
        val principal = PrincipalAccess.requireWorkspace(currentPrincipal.require())
        val normalized = ids.distinct()
        if (normalized.isEmpty() || normalized.size > EXPORT_LIMIT || normalized.any { it <= 0 }) {
            throw ClientException(ErrorCode.BAD_REQUEST, "FEEDBACK_IDS_INVALID")
        }
        val generation = snapshotProvider.require()
        val query = FeedbackListQuery()
        val access = criteriaFactory.create(query, principal)
        val rows = normalized.chunked(EXPLICIT_ID_BATCH).flatMap { batch ->
            reads.rowsByIds(generation.id, access.criteria, batch)
        }
        if (rows.map(FeedbackListRecord::sourceId).toSet() != normalized.toSet()) {
            throw ClientException(ErrorCode.AUTH_DATA_PERMISSION_DENIED, "FEEDBACK_EXPORT_SCOPE_DENIED")
        }
        return csvExporter.export(
            rows.sortedBy(FeedbackListRecord::sourceId), access.fieldPolicy, access.actorId,
            generation.id, "SELECTION", auditContext
        )
    }

    companion object {
        const val EXPORT_LIMIT = 5000
        const val EXPORT_SENTINEL = EXPORT_LIMIT + 1
        const val EXPLICIT_ID_BATCH = 200
    }
}
