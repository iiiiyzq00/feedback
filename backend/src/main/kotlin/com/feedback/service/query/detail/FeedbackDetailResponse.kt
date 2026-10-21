package com.feedback.service.query.detail

import com.fasterxml.jackson.annotation.JsonProperty
import com.feedback.model.auth.DataFieldPolicy
import com.feedback.security.FieldScopedResponse
import com.feedback.dto.attachment.FeedbackAttachmentSummaryResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Schema(description = "反馈详情；仅包含通过数据范围和字段策略校验的安全事实")
data class FeedbackDetailResponse(
    @field:Schema(description = "平台反馈 ID", example = "1024") val id: Long,
    @field:Schema(
        description = "小写来源渠道码",
        allowableValues = ["sdk", "station_feedback", "jira", "quality", "redblack"], example = "jira"
    ) val sourceChannel: String,
    @field:Schema(description = "来源系统记录 ID", example = "SPFB-1024") val sourceRecordId: String,
    @field:Schema(description = "应用版本；来源未提供时为空", example = "5.12.0") val appVersion: String?,
    @field:Schema(description = "故障发生或来源发布时间，Asia/Shanghai") val reportedAt: LocalDateTime?,
    @field:Schema(description = "故障时间精度枚举；未知值按原值返回") val faultTimePrecision: String?,
    @field:Schema(description = "故障时间事实来源枚举；未知值按原值返回") val faultTimeSource: String?,
    @field:Schema(description = "故障时间解析状态；未知值按原值返回") val faultTimeResolution: String?,
    @field:Schema(description = "平台接入时间，Asia/Shanghai") val ingestTime: LocalDateTime?,
    @field:Schema(description = "来源最后更新时间，Asia/Shanghai") val sourceUpdatedAt: LocalDateTime?,
    @field:Schema(description = "内部导入批次 ID；实时接入时为空") val importBatchId: Long?,
    @field:Schema(description = "安全问题标题；缺失时使用描述或平台 ID 兜底") val title: String,
    @field:Schema(description = "规则脱敏通过后的安全问题正文；不返回原始敏感正文") val description: String?,
    @field:Schema(description = "标准化功能模块；缺失时为 UNKNOWN") val module: String,
    @field:Schema(description = "使用阶段枚举；未知或不适用时为空") val usageStage: String?,
    @field:Schema(description = "标准化问题类型；未知时为空") val problemType: String?,
    @field:Schema(description = "问题分类；未知时为空") val issueCategory: String?,
    @field:Schema(description = "展示严重度；人工值优先于 AI 建议") val severity: String?,
    @field:Schema(description = "人工确认严重度；未确认时为空") val canonicalSeverity: String?,
    @field:Schema(description = "严重度是否仅来自 AI 建议") val severityProvisional: Boolean,
    @field:Schema(description = "严重度复核状态；未知值按原值返回") val severityReviewStatus: String?,
    @field:Schema(description = "用户情感枚举；未知时为空") val emotion: String?,
    @field:Schema(description = "可复现性枚举；未知时为空") val reproducibility: String?,
    @field:Schema(description = "统一工作流状态；未知值按原值返回") val status: String,
    @field:Schema(description = "统一分发状态；未知值按原值返回") val distributionStatus: String,
    @field:Schema(description = "正文脱敏状态；详情只允许 PASS") val maskingStatus: String,
    @field:Schema(description = "平台正文更新时间，Asia/Shanghai") val platformUpdatedAt: LocalDateTime?,
    @field:Schema(description = "工作流状态映射版本") val statusMappingVersion: String?,
    @field:Schema(description = "工作流状态映射结果") val statusMappingResult: String?,
    @field:Schema(description = "工作流状态映射时间，Asia/Shanghai") val statusMappedAt: LocalDateTime?,
    @field:Schema(description = "字段映射规则版本") val mappingRule: String?,
    @field:Schema(description = "标准化规则版本") val normalizationVersion: String?,
    @field:Schema(description = "数据质量评分，范围由质量规则定义") val qualityScore: BigDecimal?,
    @field:Schema(description = "数据质量规则版本") val qualityRuleVersion: String?,
    @field:Schema(description = "外部导入批次编码；实时接入时为空") val importBatchCode: String?,
    @field:Schema(description = "导入批次状态；实时接入时为空") val importBatchStatus: String?,
    @field:Schema(description = "当前负责人用户 ID；未分配时为空") val ownerId: Long?,
    @field:Schema(description = "当前负责人显示名称；未分配时为空") val ownerName: String?,
    @field:Schema(description = "分配记录乐观锁版本；未分配时为空") val assignmentVersion: Long?,
    @field:Schema(description = "负责人分配时间，Asia/Shanghai") val assignedAt: LocalDateTime?,
    @field:Schema(description = "省、市、区事实按顺序拼接；均缺失时为空字符串") val region: String,
    @field:Schema(description = "兼容字段，当前等于应用版本") val deviceInfo: String?,
    @field:Schema(description = "当前用户是否可打开安全只读来源外链") val externalLinkAvailable: Boolean,
    @field:Schema(description = "当前来源渠道的扩展事实") val channelMeta: FeedbackChannelMetaResponse?,
    @field:Schema(description = "关键信息和日志是否存在，不包含其正文") val diagnosticAvailability: FeedbackDiagnosticAvailabilityResponse,
    @field:Schema(description = "字段转换历史，按发生时间与 ID 正序") val fieldHistory: List<FeedbackFieldHistoryResponse>,
    @field:Schema(description = "非日志附件安全摘要；不包含存储路径或正文") val attachments: List<FeedbackAttachmentSummaryResponse>,
    @field:Schema(description = "当前数据范围内的上一条和下一条") val navigation: FeedbackNavigationResponse,
    @field:Schema(description = "当前活动 MAIN 问题簇摘要；未归簇时为空") val clusterSummary: FeedbackClusterSummaryResponse?,
    @field:Schema(description = "AI 辅助分析；没有有效摘要时为空") val aiResult: FeedbackAiAnalysisResponse?,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "AI 辅助分析结果；仅作人工判断参考")
data class FeedbackAiAnalysisResponse(
    @field:Schema(description = "AI 摘要") val summary: String?,
    @field:Schema(description = "AI 识别的应用") val application: String?,
    @field:Schema(description = "AI 识别的功能模块") val functionModule: String?,
    @field:Schema(description = "AI 建议分类") val category: String?,
    @field:Schema(description = "AI 情感判断") val sentiment: String?,
    @field:Schema(description = "AI 风险类型") val riskType: String?,
    @field:Schema(description = "AI 建议处理团队") val recommendedTeam: String?,
    @field:Schema(description = "AI 使用的数据来源快照") val dataSources: String?,
    @field:Schema(description = "AI 建议严重度") val severity: String?,
    @field:Schema(description = "AI 置信度，0 至 1") val confidence: BigDecimal?,
    @field:Schema(description = "AI 判断理由；受字段权限控制") val reasoning: String?,
    @field:Schema(description = "AI 模型版本") val modelVersion: String?,
    @field:Schema(description = "AI 处理时间，Asia/Shanghai") val processedAt: LocalDateTime?,
    @field:Schema(description = "分类 Prompt 版本") val promptVersion: String?,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "反馈关键信息与日志可用性快照；不包含诊断值，也不替代实际查看时的实时授权")
data class FeedbackDiagnosticAvailabilityResponse(
    @field:Schema(description = "是否存在至少一种可查看关键信息或日志") val available: Boolean,
    @field:Schema(description = "查询时刻可用的信息类型集合，稳定去重排序；未知 SDK 类型按大写原值保留") val types: List<String>,
    @field:Schema(description = "反馈固定为 false，不要求填写用途") val requiresPurpose: Boolean = false,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "反馈字段转换或人工修正记录")
data class FeedbackFieldHistoryResponse(
    @field:Schema(description = "审计记录 ID") val id: Long,
    @field:Schema(description = "发生变化的字段名") val fieldName: String?,
    @field:Schema(description = "变化前的脱敏值") val oldValue: String?,
    @field:Schema(description = "变化后的脱敏值") val newValue: String?,
    @field:Schema(description = "变化类型枚举") val changeType: String?,
    @field:Schema(description = "变更主体类型枚举") val changedByType: String?,
    @field:Schema(description = "变更用户 ID；系统变更时为空") val changedByUserId: Long?,
    @field:Schema(description = "字段映射版本") val mappingVersion: String?,
    @field:Schema(description = "标准化版本") val normalizationVersion: String?,
    @field:Schema(description = "脱敏后的变化原因") val changeReason: String?,
    @field:Schema(description = "变化发生时间，Asia/Shanghai") val createdAt: LocalDateTime?,
    @field:Schema(description = "操作者显示名称；系统变更时为空") val operator: String?,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "当前数据范围内按故障时间倒序排列的相邻反馈")
data class FeedbackNavigationResponse(
    @field:Schema(description = "上一条反馈 ID；不存在时为空") val previousId: Long?,
    @field:Schema(description = "下一条反馈 ID；不存在时为空") val nextId: Long?,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "当前活动 MAIN 问题簇摘要")
data class FeedbackClusterSummaryResponse(
    @field:Schema(description = "问题簇 ID") val clusterId: Long,
    @field:Schema(description = "问题簇名称") val clusterName: String?,
    @field:Schema(description = "问题簇缓存反馈总数") val total: Long?,
    @field:Schema(description = "当前反馈与问题簇相似度，0 至 1") val similarity: BigDecimal?,
    @field:Schema(description = "归簇决策理由") val decisionReason: String?,
    @field:Schema(description = "归簇决策版本") val decisionVersion: String?,
    override val fieldPolicy: DataFieldPolicy
) : FieldScopedResponse

@Schema(description = "反馈来源系统只读外链")
data class FeedbackExternalLinkResponse(
    @field:Schema(description = "通过绝对 HTTPS URI、非空 host、无 user-info 结构校验的地址；当前不表示 host 已进入白名单") val url: String,
    @field:Schema(description = "大写来源渠道码", allowableValues = ["JIRA", "REDBLACK"], example = "JIRA") val channel: String,
    @field:Schema(description = "固定为 true，平台不回写来源系统") val readOnly: Boolean = true
)

@Schema(description = "反馈渠道扩展事实；字段保持历史 snake_case JSON 契约")
sealed interface FeedbackChannelMetaResponse : FieldScopedResponse

@Schema(description = "SDK 渠道扩展事实")
data class SdkFeedbackChannelMetaResponse(
    @field:Schema(description = "客户端反馈 ID") @field:JsonProperty("client_feedback_id") val clientFeedbackId: String?,
    @field:Schema(description = "应用标识") @field:JsonProperty("app_id") val appId: String?,
    @field:Schema(description = "应用包名") @field:JsonProperty("package_name") val packageName: String?,
    @field:Schema(description = "操作系统版本") @field:JsonProperty("os_version") val osVersion: String?,
    @field:Schema(description = "网络类型枚举") @field:JsonProperty("network_type") val networkType: String?,
    @field:Schema(description = "设备型号；受字段权限控制") @field:JsonProperty("device_model") val deviceModel: String?,
    @field:Schema(description = "屏幕方向枚举") @field:JsonProperty("screen_orientation") val screenOrientation: String?,
    @field:Schema(description = "输入方式快照") @field:JsonProperty("input_methods") val inputMethods: String?,
    @field:Schema(description = "反馈触发方式枚举") @field:JsonProperty("trigger_type") val triggerType: String?,
    @field:Schema(description = "电量百分比") @field:JsonProperty("battery_level") val batteryLevel: BigDecimal?,
    @field:Schema(description = "可用存储，单位 MB") @field:JsonProperty("storage_available_mb") val storageAvailableMb: Long?,
    @field:Schema(description = "SDK 接入模式") @field:JsonProperty("integration_mode") val integrationMode: String?,
    @field:Schema(description = "上下文来源") @field:JsonProperty("context_source") val contextSource: String?,
    @field:Schema(description = "页面路由；受字段权限控制") @field:JsonProperty("page_route") val pageRoute: String?,
    @field:Schema(description = "业务 ID；受字段权限控制") @field:JsonProperty("business_id") val businessId: String?,
    @field:Schema(description = "脱敏后的崩溃堆栈") @field:JsonProperty("crash_stack") val crashStack: String?,
    @field:Schema(description = "课程 ID；受字段权限控制") @field:JsonProperty("course_id") val courseId: String?,
    @field:Schema(description = "题目 ID；受字段权限控制") @field:JsonProperty("question_id") val questionId: String?,
    @field:Schema(description = "订单 ID；受字段权限控制") @field:JsonProperty("order_id") val orderId: String?,
    @field:Schema(description = "脱敏后的接口请求摘要") @field:JsonProperty("api_request_summary") val apiRequestSummary: String?,
    @field:Schema(description = "状态机 JSON 快照") @field:JsonProperty("state_machine") val stateMachine: String?,
    @field:Schema(description = "自定义事件 JSON 快照") @field:JsonProperty("custom_events") val customEvents: String?,
    override val fieldPolicy: DataFieldPolicy
) : FeedbackChannelMetaResponse

@Schema(description = "Jira 渠道扩展事实")
data class JiraFeedbackChannelMetaResponse(
    @field:Schema(description = "Jira 问题键") @field:JsonProperty("jira_key") val jiraKey: String?,
    @field:Schema(description = "Jira 原始优先级") @field:JsonProperty("priority_raw") val priorityRaw: String?,
    @field:Schema(description = "Jira 原始状态") @field:JsonProperty("jira_status_raw") val jiraStatusRaw: String?,
    @field:Schema(description = "Jira 项目键") @field:JsonProperty("project_key") val projectKey: String?,
    @field:Schema(description = "Jira 项目名称") @field:JsonProperty("project_name") val projectName: String?,
    @field:Schema(description = "Jira 解决结果") val resolution: String?,
    @field:Schema(description = "脱敏后的 Jira 经办人") val assignee: String?,
    @field:Schema(description = "Jira 创建时间") @field:JsonProperty("jira_created_at") val jiraCreatedAt: LocalDateTime?,
    @field:Schema(description = "Jira 更新时间") @field:JsonProperty("jira_updated_at") val jiraUpdatedAt: LocalDateTime?,
    @field:Schema(description = "Jira 解决时间") @field:JsonProperty("resolved_at") val resolvedAt: LocalDateTime?,
    @field:Schema(description = "Jira 影响版本") @field:JsonProperty("affected_version") val affectedVersion: String?,
    @field:Schema(description = "Jira 修复版本") @field:JsonProperty("fix_version") val fixVersion: String?,
    @field:Schema(description = "Jira 原始模块") @field:JsonProperty("module_raw") val moduleRaw: String?,
    @field:Schema(description = "Jira 到期日期") @field:JsonProperty("due_date") val dueDate: LocalDate?,
    @field:Schema(description = "Jira 标签一") @field:JsonProperty("label_1") val label1: String?,
    @field:Schema(description = "Jira 标签二") @field:JsonProperty("label_2") val label2: String?,
    @field:Schema(description = "Jira 标签三") @field:JsonProperty("label_3") val label3: String?,
    @field:Schema(description = "脱敏后的 Jira 评论摘要") @field:JsonProperty("comment_summary") val commentSummary: String?,
    override val fieldPolicy: DataFieldPolicy
) : FeedbackChannelMetaResponse

@Schema(description = "质量平台渠道扩展事实")
data class QualityFeedbackChannelMetaResponse(
    @field:Schema(description = "退换货工单号") @field:JsonProperty("return_order_no") val returnOrderNo: String?,
    @field:Schema(description = "产品名称") @field:JsonProperty("product_name") val productName: String?,
    @field:Schema(description = "产品型号") @field:JsonProperty("product_model") val productModel: String?,
    @field:Schema(description = "派工日期") @field:JsonProperty("dispatch_date") val dispatchDate: LocalDate?,
    @field:Schema(description = "激活日期") @field:JsonProperty("activation_date") val activationDate: LocalDate?,
    @field:Schema(description = "维修区域") @field:JsonProperty("repair_region") val repairRegion: String?,
    @field:Schema(description = "来源省份") val province: String?,
    @field:Schema(description = "来源城市") val city: String?,
    @field:Schema(description = "来源区县") val county: String?,
    @field:Schema(description = "来源区域") val district: String?,
    @field:Schema(description = "原始申请类型") @field:JsonProperty("application_type_raw") val applicationTypeRaw: String?,
    @field:Schema(description = "是否申请换货") @field:JsonProperty("exchange_request") val exchangeRequest: Boolean?,
    @field:Schema(description = "脱敏后的退货原因") @field:JsonProperty("return_reason") val returnReason: String?,
    @field:Schema(description = "故障确认结果") @field:JsonProperty("fault_confirmed") val faultConfirmed: String?,
    @field:Schema(description = "脱敏后的故障备注") @field:JsonProperty("fault_remark") val faultRemark: String?,
    @field:Schema(description = "商家退货申请时间") @field:JsonProperty("merchant_return_apply_date") val merchantReturnApplyDate: LocalDateTime?,
    @field:Schema(description = "实际发货日期") @field:JsonProperty("actual_shipping_date") val actualShippingDate: LocalDate?,
    override val fieldPolicy: DataFieldPolicy
) : FeedbackChannelMetaResponse

@Schema(description = "红黑榜渠道扩展事实；不包含外链 URL")
data class RedblackFeedbackChannelMetaResponse(
    @field:Schema(description = "来源原始 ID") @field:JsonProperty("original_id_raw") val originalIdRaw: String?,
    @field:Schema(description = "来源标题") val headline: String?,
    @field:Schema(description = "公开作者或脱敏作者") val author: String?,
    @field:Schema(description = "来源平台") @field:JsonProperty("source_platform") val sourcePlatform: String?,
    @field:Schema(description = "发布类型名称") @field:JsonProperty("pub_type_name") val pubTypeName: String?,
    @field:Schema(description = "榜单类型") @field:JsonProperty("list_type") val listType: String?,
    @field:Schema(description = "来源板块") @field:JsonProperty("section_raw") val sectionRaw: String?,
    @field:Schema(description = "来源文件夹") @field:JsonProperty("folder_name") val folderName: String?,
    @field:Schema(description = "来源关键词") val keyword: String?,
    @field:Schema(description = "来源情感类型") @field:JsonProperty("emotion_type_raw") val emotionTypeRaw: String?,
    @field:Schema(description = "评价对象") @field:JsonProperty("evaluation_object") val evaluationObject: String?,
    @field:Schema(description = "评价维度") @field:JsonProperty("evaluation_dimension") val evaluationDimension: String?,
    @field:Schema(description = "来源影响等级") @field:JsonProperty("impact_level_raw") val impactLevelRaw: String?,
    @field:Schema(description = "数据周期") @field:JsonProperty("data_period") val dataPeriod: String?,
    @field:Schema(description = "脱敏后的来源部门") @field:JsonProperty("source_department_masked") val sourceDepartmentMasked: String?,
    @field:Schema(description = "脱敏后的来源角色") @field:JsonProperty("source_role_masked") val sourceRoleMasked: String?,
    @field:Schema(description = "推送时间") @field:JsonProperty("push_time") val pushTime: LocalDateTime?,
    @field:Schema(description = "发布时间") @field:JsonProperty("pub_time") val pubTime: LocalDateTime?,
    @field:Schema(description = "来源创建时间") @field:JsonProperty("create_time") val createTime: LocalDateTime?,
    @field:Schema(description = "来源更新时间") @field:JsonProperty("update_time") val updateTime: LocalDateTime?,
    @field:Schema(description = "来源侧删除标记") @field:JsonProperty("is_deleted_src") val sourceDeleted: Boolean?,
    override val fieldPolicy: DataFieldPolicy
) : FeedbackChannelMetaResponse

@Schema(description = "学习机站内反馈渠道扩展事实")
data class StationFeedbackChannelMetaResponse(
    @field:Schema(description = "来源 API 版本") @field:JsonProperty("source_api_version") val sourceApiVersion: String?,
    @field:Schema(description = "来源产品 ID") @field:JsonProperty("product_id") val productId: String?,
    @field:Schema(description = "来源产品名称") @field:JsonProperty("source_product_name") val sourceProductName: String?,
    @field:Schema(description = "来源应用 ID") @field:JsonProperty("source_app_id") val sourceAppId: String?,
    @field:Schema(description = "来源应用名称") @field:JsonProperty("source_app_name") val sourceAppName: String?,
    @field:Schema(description = "来源平台") @field:JsonProperty("source_platform") val sourcePlatform: String?,
    @field:Schema(description = "来源反馈类型") @field:JsonProperty("source_feedback_type") val sourceFeedbackType: String?,
    @field:Schema(description = "来源子渠道") @field:JsonProperty("source_sub_channel") val sourceSubChannel: String?,
    @field:Schema(description = "脱敏后的来源描述") @field:JsonProperty("source_description") val sourceDescription: String?,
    @field:Schema(description = "属性 Schema 版本") @field:JsonProperty("attribute_schema_version") val attributeSchemaVersion: String?,
    @field:Schema(description = "属性映射状态") @field:JsonProperty("attribute_mapping_status") val attributeMappingStatus: String?,
    @field:Schema(description = "联系方式治理状态，不包含联系方式") @field:JsonProperty("contact_status") val contactStatus: String?,
    @field:Schema(description = "来源接入状态") @field:JsonProperty("ingest_status") val ingestStatus: String?,
    @field:Schema(description = "来源处理阶段") @field:JsonProperty("processing_stage") val processingStage: String?,
    @field:Schema(description = "处理重试次数") @field:JsonProperty("retry_count") val retryCount: Int?,
    @field:Schema(description = "来源处理时间") @field:JsonProperty("processed_at") val processedAt: LocalDateTime?,
    @field:Schema(description = "来源记录创建时间") @field:JsonProperty("created_at") val createdAt: LocalDateTime?,
    override val fieldPolicy: DataFieldPolicy
) : FeedbackChannelMetaResponse
