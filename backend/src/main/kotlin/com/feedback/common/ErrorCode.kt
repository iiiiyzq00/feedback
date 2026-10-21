package com.feedback.common

import org.springframework.http.HttpStatus

/** 统一异常体系允许的错误归属层级。 */
enum class ErrorLayer(val metricTag: String) {
    CLIENT("client"),
    SERVICE("service"),
    REMOTE("remote"),
}

/**
 * 业务错误码枚举。
 *
 * [httpStatus] 是统一异常体系中错误码到 HTTP 状态的唯一映射来源。
 *
 * 1xxx — 通用错误
 * 2xxx — SDK/接入错误
 * 3xxx — 脱敏/安全错误
 * 4xxx — 导入/表格错误
 * 5xxx — AI 错误
 * 6xxx — 告警错误
 * 7xxx — 权限错误
 * 8xxx — 服务与业务状态错误
 * 9xxx — 外部依赖错误
 */
enum class ErrorCode(
    val code: Int,
    val message: String,
    val httpStatus: HttpStatus,
    val retryable: Boolean = false
) {

    // 通用错误 (1xxx)；成功响应协议由 ApiResponse 独立维护。
    BAD_REQUEST(1001, "请求参数错误", HttpStatus.BAD_REQUEST),
    NOT_FOUND(1002, "资源不存在", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR(1003, "服务暂时不可用，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR, true),
    ATTACHMENT_ACCESS_CONFLICT(1004, "附件授权无效、已过期或已消费", HttpStatus.CONFLICT),

    // SDK 接入 (2xxx)
    SDK_SIGNATURE_INVALID(2001, "SDK 签名校验失败", HttpStatus.BAD_REQUEST),
    SDK_NONCE_REPLAY(2002, "SDK Nonce 重放", HttpStatus.BAD_REQUEST),
    SDK_RATE_LIMITED(2003, "SDK 请求频率超限", HttpStatus.TOO_MANY_REQUESTS, true),
    SDK_IDEMPOTENT_CONFLICT(2004, "SDK 幂等冲突 — 返回已有 receipt", HttpStatus.CONFLICT),
    SDK_ENVELOPE_INVALID(2005, "SDK 加密信封结构无效", HttpStatus.BAD_REQUEST),
    SDK_CIPHER_TOO_LARGE(2006, "SDK 密文超过大小限制", HttpStatus.BAD_REQUEST),
    SDK_SCHEMA_VERSION_UNSUPPORTED(2007, "SDK Schema 版本不受支持", HttpStatus.BAD_REQUEST),
    SDK_RECEIPT_NOT_FOUND(2008, "SDK receipt 不存在", HttpStatus.BAD_REQUEST),
    SDK_ATTACHMENT_KEY_REQUIRED(2009, "SDK 附件缺少独立封装密钥", HttpStatus.BAD_REQUEST),
    SDK_ATTACHMENT_KEY_INVALID(2010, "SDK 附件封装密钥无效", HttpStatus.BAD_REQUEST),
    SDK_RECEIPT_FORBIDDEN(2011, "无权访问该 SDK receipt", HttpStatus.FORBIDDEN),
    SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE(
        2012,
        "SDK 附件解密服务暂时不可用",
        HttpStatus.SERVICE_UNAVAILABLE,
        true
    ),

    // 脱敏 (3xxx)
    MASKING_DECRYPT_FAILED(3001, "解密失败 — 密钥或格式错误", HttpStatus.INTERNAL_SERVER_ERROR, true),
    MASKING_NER_FAILED(3002, "NER 脱敏处理异常", HttpStatus.INTERNAL_SERVER_ERROR, true),
    MASKING_REVIEW_NEEDED(3003, "脱敏结果需要人工审核", HttpStatus.CONFLICT),
    MASKING_FAILED(3004, "脱敏失败 — 数据隔离", HttpStatus.INTERNAL_SERVER_ERROR, true),

    // 导入 (4xxx)
    IMPORT_FILE_EMPTY(4001, "上传文件为空", HttpStatus.BAD_REQUEST),
    IMPORT_FORMAT_INVALID(4002, "文件格式无效（仅支持 .xlsx/.csv）", HttpStatus.BAD_REQUEST),
    IMPORT_SHA256_DUPLICATE(4003, "文件 SHA-256 重复", HttpStatus.CONFLICT),
    IMPORT_MAPPING_FAILED(4004, "字段映射失败", HttpStatus.BAD_REQUEST),
    IMPORT_ROLLBACK_EXPIRED(4005, "超过 24h 回滚时限", HttpStatus.CONFLICT),
    IMPORT_BATCH_NOT_FOUND(4006, "批次不存在", HttpStatus.NOT_FOUND),

    // AI 类（5xxx）
    AI_LLM_UNAVAILABLE(5001, "智能分析服务暂时不可用", HttpStatus.BAD_GATEWAY, true),
    AI_LLM_TIMEOUT(5002, "智能分析处理超时", HttpStatus.GATEWAY_TIMEOUT, true),
    AI_TASK_EXPIRED(5003, "AI 任务租约过期", HttpStatus.CONFLICT),
    AI_DEGRADED(5004, "AI 服务降级 — 使用规则兜底", HttpStatus.SERVICE_UNAVAILABLE),
    AI_LLM_CONFIGURATION_INVALID(5005, "智能分析服务配置不可用", HttpStatus.INTERNAL_SERVER_ERROR),
    AI_DECISION_UNSUPPORTED(5006, "当前智能分析决策不受支持", HttpStatus.INTERNAL_SERVER_ERROR),

    // 告警 (6xxx)
    ALERT_RULE_NOT_FOUND(6001, "告警规则不存在", HttpStatus.NOT_FOUND),
    ALERT_INCIDENT_NOT_FOUND(6002, "Incident 不存在", HttpStatus.NOT_FOUND),
    ALERT_ESCALATION_FAILED(6003, "告警升级失败", HttpStatus.INTERNAL_SERVER_ERROR, true),

    // 权限 (7xxx)
    AUTH_UNAUTHORIZED(7001, "未认证 — 请先登录", HttpStatus.UNAUTHORIZED),
    AUTH_FORBIDDEN(7002, "无权限 — 当前角色不可执行此操作", HttpStatus.FORBIDDEN),
    AUTH_TOKEN_EXPIRED(7003, "JWT 已过期", HttpStatus.UNAUTHORIZED),
    AUTH_DATA_PERMISSION_DENIED(7004, "行级权限不足", HttpStatus.FORBIDDEN),
    AUTH_LOGIN_RATE_LIMITED(7005, "登录尝试过于频繁，请稍后重试", HttpStatus.TOO_MANY_REQUESTS, true),

    // 服务与业务状态 (8xxx)
    CLUSTER_REVIEW_CONFLICT(8001, "审核任务已被其他人处理，请刷新后重试", HttpStatus.CONFLICT),
    RCA_REVIEW_CONFLICT(8002, "RCA 已被其他审核人更新，请刷新后重试", HttpStatus.CONFLICT),
    KNOWLEDGE_VERSION_CONFLICT(8003, "知识条目已发生变化，请刷新后重试", HttpStatus.CONFLICT),
    STATION_IDEMPOTENCY_CONFLICT(8004, "幂等键已绑定其他请求内容", HttpStatus.CONFLICT),
    APPLICATION_IDENTITY_CONFLICT(8005, "应用身份已发生变化，请刷新后重试", HttpStatus.CONFLICT),
    OUTBOUND_PRIVACY_CHECK_FAILED(8101, "出站隐私检查失败", HttpStatus.INTERNAL_SERVER_ERROR, true),
    LEGACY_SDK_PAYLOAD_UNREADABLE(8102, "历史 SDK 负载无法读取", HttpStatus.INTERNAL_SERVER_ERROR),
    FEEDBACK_LIST_PROJECTION_BUILD_FAILED(8103, "反馈列表投影构建失败", HttpStatus.INTERNAL_SERVER_ERROR, true),
    FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED(8104, "反馈列表投影尚未追平", HttpStatus.SERVICE_UNAVAILABLE, true),
    STATION_DIRTY_MARKING_FAILED(8105, "站点数据脏标记失败", HttpStatus.INTERNAL_SERVER_ERROR, true),
    APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED(
        8106,
        "应用身份来源数据暂时不可用",
        HttpStatus.SERVICE_UNAVAILABLE,
        true
    ),
    CLUSTER_MAIN_RACE(8107, "问题簇并发状态冲突，请重试", HttpStatus.CONFLICT, true),
    JIRA_SYNC_CONTRACT_INVALID(8108, "Jira 同步契约或分页状态无效", HttpStatus.INTERNAL_SERVER_ERROR),
    FEISHU_CONFIGURATION_INVALID(8109, "飞书认证配置不可用", HttpStatus.INTERNAL_SERVER_ERROR),
    FEISHU_SYNC_ALREADY_RUNNING(8110, "飞书组织同步正在运行，请稍后重试", HttpStatus.CONFLICT, true),
    CLUSTER_SHADOW_EVALUATION_DISABLED(8111, "问题簇影子评测未启用", HttpStatus.SERVICE_UNAVAILABLE),
    FEEDBACK_READ_UNAVAILABLE(8112, "反馈数据暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    ATTACHMENT_SERVICE_UNAVAILABLE(8113, "附件服务暂时不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE, true),
    ATTACHMENT_NOT_READY(8114, "附件预览尚未就绪，请稍后重试", HttpStatus.CONFLICT, true),

    // 外部依赖 (9xxx)
    JIRA_UNAVAILABLE(9001, "Jira 服务暂时不可用", HttpStatus.BAD_GATEWAY, true),
    JIRA_RATE_LIMITED(9002, "Jira 请求频率受限", HttpStatus.SERVICE_UNAVAILABLE, true),
    OLD_FBP_UNAVAILABLE(9003, "旧反馈平台暂时不可用", HttpStatus.BAD_GATEWAY, true),
    REMOTE_TIMEOUT(9004, "外部服务响应超时", HttpStatus.GATEWAY_TIMEOUT, true),
    JIRA_AUTHENTICATION_FAILED(9005, "Jira 认证失败", HttpStatus.BAD_GATEWAY),
    JIRA_ACCESS_FORBIDDEN(9006, "Jira 拒绝访问", HttpStatus.BAD_GATEWAY),
    JIRA_RESPONSE_INVALID(9007, "Jira 响应不符合约定", HttpStatus.BAD_GATEWAY),
    JIRA_REQUEST_REJECTED(9008, "Jira 拒绝了请求", HttpStatus.BAD_GATEWAY),
    OLD_FBP_AUTHENTICATION_FAILED(9009, "旧反馈平台认证失败", HttpStatus.BAD_GATEWAY),
    OLD_FBP_RESPONSE_INVALID(9010, "旧反馈平台响应不符合约定", HttpStatus.BAD_GATEWAY),
    OLD_FBP_REQUEST_REJECTED(9011, "旧反馈平台拒绝了请求", HttpStatus.BAD_GATEWAY),
    FEISHU_UNAVAILABLE(9012, "飞书服务暂时不可用", HttpStatus.BAD_GATEWAY, true),
    FEISHU_RATE_LIMITED(9013, "飞书请求频率受限", HttpStatus.SERVICE_UNAVAILABLE, true),
    FEISHU_AUTHENTICATION_FAILED(9014, "飞书应用认证失败", HttpStatus.BAD_GATEWAY),
    FEISHU_RESPONSE_INVALID(9015, "飞书响应不符合约定", HttpStatus.BAD_GATEWAY),
    FEISHU_REQUEST_REJECTED(9016, "飞书拒绝了请求", HttpStatus.BAD_GATEWAY),
    STATION_ATTACHMENT_UNAVAILABLE(9017, "站点附件来源暂时不可用", HttpStatus.BAD_GATEWAY, true),
    ATTACHMENT_STORAGE_UNAVAILABLE(9018, "附件对象存储暂时不可用", HttpStatus.BAD_GATEWAY, true);

    /**
     * 显式、穷尽地声明错误所属层级。
     *
     * 此处不使用 HTTP 状态或编号区间推导，避免把传输语义误当成异常责任边界；
     * 新增错误码时，编译器会要求同步完成层级归类。
     */
    val layer: ErrorLayer
        get() = when (this) {
            BAD_REQUEST,
            NOT_FOUND,
            ATTACHMENT_ACCESS_CONFLICT,
            SDK_SIGNATURE_INVALID,
            SDK_NONCE_REPLAY,
            SDK_RATE_LIMITED,
            SDK_IDEMPOTENT_CONFLICT,
            SDK_ENVELOPE_INVALID,
            SDK_CIPHER_TOO_LARGE,
            SDK_SCHEMA_VERSION_UNSUPPORTED,
            SDK_RECEIPT_NOT_FOUND,
            SDK_ATTACHMENT_KEY_REQUIRED,
            SDK_ATTACHMENT_KEY_INVALID,
            SDK_RECEIPT_FORBIDDEN,
            IMPORT_FILE_EMPTY,
            IMPORT_FORMAT_INVALID,
            IMPORT_SHA256_DUPLICATE,
            IMPORT_MAPPING_FAILED,
            IMPORT_ROLLBACK_EXPIRED,
            IMPORT_BATCH_NOT_FOUND,
            ALERT_RULE_NOT_FOUND,
            ALERT_INCIDENT_NOT_FOUND,
            AUTH_UNAUTHORIZED,
            AUTH_FORBIDDEN,
            AUTH_TOKEN_EXPIRED,
            AUTH_DATA_PERMISSION_DENIED,
            AUTH_LOGIN_RATE_LIMITED -> ErrorLayer.CLIENT

            INTERNAL_ERROR,
            SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE,
            MASKING_DECRYPT_FAILED,
            MASKING_NER_FAILED,
            MASKING_REVIEW_NEEDED,
            MASKING_FAILED,
            AI_TASK_EXPIRED,
            AI_DEGRADED,
            AI_LLM_CONFIGURATION_INVALID,
            AI_DECISION_UNSUPPORTED,
            ALERT_ESCALATION_FAILED,
            CLUSTER_REVIEW_CONFLICT,
            RCA_REVIEW_CONFLICT,
            KNOWLEDGE_VERSION_CONFLICT,
            STATION_IDEMPOTENCY_CONFLICT,
            APPLICATION_IDENTITY_CONFLICT,
            OUTBOUND_PRIVACY_CHECK_FAILED,
            LEGACY_SDK_PAYLOAD_UNREADABLE,
            FEEDBACK_LIST_PROJECTION_BUILD_FAILED,
            FEEDBACK_LIST_PROJECTION_CATCH_UP_REQUIRED,
            STATION_DIRTY_MARKING_FAILED,
            APPLICATION_IDENTITY_SOURCE_INTEGRITY_FAILED,
            CLUSTER_MAIN_RACE,
            JIRA_SYNC_CONTRACT_INVALID,
            FEISHU_CONFIGURATION_INVALID,
            FEISHU_SYNC_ALREADY_RUNNING,
            CLUSTER_SHADOW_EVALUATION_DISABLED,
            FEEDBACK_READ_UNAVAILABLE,
            ATTACHMENT_SERVICE_UNAVAILABLE,
            ATTACHMENT_NOT_READY -> ErrorLayer.SERVICE

            AI_LLM_UNAVAILABLE,
            AI_LLM_TIMEOUT,
            JIRA_UNAVAILABLE,
            JIRA_RATE_LIMITED,
            OLD_FBP_UNAVAILABLE,
            REMOTE_TIMEOUT,
            JIRA_AUTHENTICATION_FAILED,
            JIRA_ACCESS_FORBIDDEN,
            JIRA_RESPONSE_INVALID,
            JIRA_REQUEST_REJECTED,
            OLD_FBP_AUTHENTICATION_FAILED,
            OLD_FBP_RESPONSE_INVALID,
            OLD_FBP_REQUEST_REJECTED,
            FEISHU_UNAVAILABLE,
            FEISHU_RATE_LIMITED,
            FEISHU_AUTHENTICATION_FAILED,
            FEISHU_RESPONSE_INVALID,
            FEISHU_REQUEST_REJECTED,
            STATION_ATTACHMENT_UNAVAILABLE,
            ATTACHMENT_STORAGE_UNAVAILABLE -> ErrorLayer.REMOTE
        }
}
