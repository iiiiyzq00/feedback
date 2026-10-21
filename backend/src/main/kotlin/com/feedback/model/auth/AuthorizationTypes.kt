package com.feedback.model.auth

import java.util.Locale

/**
 * 平台控制的角色目录。
 *
 * [code] 是数据库、HTTP 与 Spring authority 的稳定兼容编码；应用内部不得重新拼写角色字符串。
 * PHASE_ONE_USER 仅用于兼容迁移前存量授权，新分配必须使用拆分后的技术/产品角色。
 */
enum class RoleCode(val code: String) {
    ADMIN("ADMIN"),
    OPERATOR("OPERATOR"),
    AUDITOR("AUDITOR"),
    USER("USER"),
    PHASE_ONE_USER("PHASE_ONE_USER"),
    PHASE_ONE_TECHNICAL("PHASE_ONE_TECHNICAL"),
    PHASE_ONE_PRODUCT("PHASE_ONE_PRODUCT");

    companion object {
        /** 宽容解析 HTTP 边界角色码；仅做大小写规范化，不改变稳定 code。 */
        fun fromBoundary(value: String?): RoleCode? = value?.trim()?.uppercase(Locale.ROOT)?.let(::fromStorage)
        /** 严格按数据库稳定 code 查找角色，未知值返回 null 供边界决定错误语义。 */
        fun fromStorage(value: String?): RoleCode? = entries.firstOrNull { it.code == value }
        /** 严格解析持久化角色；未知编码抛错并使授权装配失败关闭。 */
        fun requireStorage(value: String): RoleCode = requireNotNull(fromStorage(value)) {
            "未知角色编码，授权装配失败关闭: $value"
        }
    }
}

/** 账号认证来源；稳定 code 与现有 t_auth_user.auth_provider、HTTP 响应完全一致。 */
enum class AuthProvider(val code: String) {
    LOCAL("LOCAL"),
    SSO("SSO");

    companion object {
        /** 按数据库稳定 code 查找认证来源，不做协议值迁移。 */
        fun fromStorage(value: String?): AuthProvider? = entries.firstOrNull { it.code == value }
        /** 严格解析持久化认证来源；未知值禁止恢复会话。 */
        fun requireStorage(value: String): AuthProvider = requireNotNull(fromStorage(value)) {
            "未知认证来源，登录失败关闭: $value"
        }
    }
}

/** 数据权限主体类型，对应 t_auth_data_permission.subject_type。 */
enum class DataPermissionSubjectType(val code: String) { USER("USER"), ROLE("ROLE") }

/**
 * 数据范围维度，对应数据库/HTTP 的 CHANNEL、MODULE、REGION、FIELD、TENANT。
 *
 * TENANT 与 CHANNEL 是**互相独立**的两个维度：渠道是同一租户内的业务分类，租户是数据归属方。
 * 因此 TENANT 的取值不与渠道取值共用，判定也各自独立（见 [DataAccessScope.permitsTenant]）。
 */
enum class DataScopeType(val code: String) {
    CHANNEL("CHANNEL"), MODULE("MODULE"), REGION("REGION"), FIELD("FIELD"), TENANT("TENANT");

    companion object {
        /** 将 HTTP 数据范围类型规范化为封闭领域值。 */
        fun fromBoundary(value: String?): DataScopeType? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库范围类型；未知值使数据权限计算失败关闭。 */
        fun requireStorage(value: String): DataScopeType = requireNotNull(entries.firstOrNull { it.code == value }) {
            "未知数据范围类型，授权计算失败关闭: $value"
        }
    }
}

/** 数据权限效果；DENY 在范围合并时始终优先于 ALLOW。 */
enum class DataPermissionEffect(val code: String) {
    ALLOW("ALLOW"), DENY("DENY");

    companion object {
        /** 将 HTTP 权限效果规范化为封闭领域值。 */
        fun fromBoundary(value: String?): DataPermissionEffect? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库效果；未知值不能降级为 ALLOW。 */
        fun requireStorage(value: String): DataPermissionEffect = requireNotNull(entries.firstOrNull { it.code == value }) {
            "未知数据权限效果，授权计算失败关闭: $value"
        }
    }
}

/** 数据访问级别；当前读取链只消费 READ，WRITE 仍保留原有 HTTP/数据库编码。 */
enum class DataAccessLevel(val code: String) {
    READ("READ"), WRITE("WRITE");

    companion object {
        /** 将 HTTP 访问级别规范化为封闭领域值。 */
        fun fromBoundary(value: String?): DataAccessLevel? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库访问级别，未知值拒绝进入授权计算。 */
        fun requireStorage(value: String): DataAccessLevel = requireNotNull(entries.firstOrNull { it.code == value }) {
            "未知数据访问级别，授权计算失败关闭: $value"
        }
    }
}

/**
 * 可扩展权限目录的受校验值对象。
 *
 * 权限码由数据库目录持续扩展，因此不做封闭 enum；所有入口统一校验格式，避免业务层传播任意字符串。
 */
@JvmInline
value class PermissionCode private constructor(val code: String) : Comparable<PermissionCode> {
    /** 以稳定 code 排序，保证响应和批量写入顺序可重复。 */
    override fun compareTo(other: PermissionCode): Int = code.compareTo(other.code)
    /** 日志和集合展示只返回经过校验的稳定 code。 */
    override fun toString(): String = code

    companion object {
        private val FORMAT = Regex("^[a-z][a-z0-9_]{1,49}:[a-z][a-z0-9_]{1,99}$")

        /** 校验可扩展 HTTP 权限码格式，合法时构造值对象。 */
        fun fromBoundary(value: String?): PermissionCode? = value?.trim()?.takeIf(FORMAT::matches)?.let(::PermissionCode)
        /** 要求 HTTP 权限码合法，否则以客户端输入错误拒绝。 */
        fun requireBoundary(value: String): PermissionCode = requireNotNull(fromBoundary(value)) { "权限码格式非法" }
        /** 要求数据库权限码合法，异常时授权装配失败关闭。 */
        fun requireStorage(value: String): PermissionCode = requireNotNull(value.takeIf(FORMAT::matches)?.let(::PermissionCode)) {
            "数据库存在非法权限码，授权装配失败关闭: $value"
        }
    }
}

/** 权限目录中需要被代码引用的稳定权限键；新增目录项仍通过 [PermissionCode] 动态传播。 */
object PlatformPermissions {
    val STATION_INGEST = PermissionCode.requireStorage("station:ingest")
    val AUDIT_LOGS = PermissionCode.requireStorage("access:audit_logs")
    val MASKING_VIEW = PermissionCode.requireStorage("access:masking_view")
    val MASKING_EDIT = PermissionCode.requireStorage("access:masking_edit")
    val ALERT_LOOP_HEALTH = PermissionCode.requireStorage("access:alert_loop_health")
    val SYSTEM_CONFIG = PermissionCode.requireStorage("access:system_config")
    val ONCALL_VIEW = PermissionCode.requireStorage("access:oncall_view")
    val ONCALL_EDIT_OWN = PermissionCode.requireStorage("access:oncall_edit_own")
    val ROUTING_CONFIG = PermissionCode.requireStorage("access:routing_config")
    val MASKING_QUARANTINE = PermissionCode.requireStorage("access:masking_quarantine")
    val ALERT_CENTER = PermissionCode.requireStorage("access:alert_center")
    val ACK_ALERT = PermissionCode.requireStorage("action:ack_alert")
    val FEEDBACK_EXPORT = PermissionCode.requireStorage("feedback:export")
    val UPLOAD_FILE = PermissionCode.requireStorage("action:upload_file")
    val IMPORT_MANAGEMENT = PermissionCode.requireStorage("access:import_management")
    val DASHBOARD_ANALYSIS = PermissionCode.requireStorage("access:dashboard_analysis")
    val DASHBOARD_READ = PermissionCode.requireStorage("dashboard:read")
    val EDIT_STATUS = PermissionCode.requireStorage("action:edit_status")
    val WORKSPACE = PermissionCode.requireStorage("access:phase_one_workspace")
    val RAW_CONTENT = PermissionCode.requireStorage("access:phase_one_raw_content")
    val AI_REPORTS = PermissionCode.requireStorage("access:ai_reports")
    val USER_MANAGEMENT = PermissionCode.requireStorage("access:user_management")
    val ADMIN_MANAGE = PermissionCode.requireStorage("admin:manage")
    val ONCALL_EDIT_ALL = PermissionCode.requireStorage("access:oncall_edit_all")
    val GENERATE_REPORTS = PermissionCode.requireStorage("action:generate_reports")
    val DELETE_REPORTS = PermissionCode.requireStorage("action:delete_reports")
    val REVIEW_MASKING_RAW = PermissionCode.requireStorage("action:review_masking_raw")
    val INSPECT_MASKING_RAW = PermissionCode.requireStorage("action:inspect_masking_raw")
    val GENERATE_JIRA_KNOWLEDGE_CANDIDATES =
        PermissionCode.requireStorage("action:generate_jira_knowledge_candidates")
}

/** 数据范围通配符的集中兼容值，避免授权算法散落特殊字符串。 */
object DataScopeValues {
    const val ALL = "*"
}

/** 飞书组织事实匹配规则，顺序同时定义授权优先级。 */
enum class FeishuRuleType(val code: String, val precedence: Int) {
    USER("USER", 0), USER_GROUP("USER_GROUP", 1), DEPARTMENT("DEPARTMENT", 2), DEFAULT("DEFAULT", 3);

    companion object {
        /** 将 HTTP 规则类型规范化为封闭领域值。 */
        fun fromBoundary(value: String?): FeishuRuleType? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库规则类型，未知值终止授权同步。 */
        fun requireStorage(value: String) = requireNotNull(entries.firstOrNull { it.code == value })
    }
}

/** 飞书规则授予本地角色或数据范围。 */
enum class FeishuGrantKind(val code: String) {
    ROLE("ROLE"), DATA_SCOPE("DATA_SCOPE");

    companion object {
        /** 将 HTTP 授权种类规范化为角色或数据范围。 */
        fun fromBoundary(value: String?): FeishuGrantKind? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库授权种类，未知值不得进入规则计算。 */
        fun requireStorage(value: String) = requireNotNull(entries.firstOrNull { it.code == value })
    }
}

/** 飞书规则审批状态机；只有 APPROVED 可进入运行时授权计算。 */
enum class FeishuMappingDecision(val code: String) {
    DRAFT("DRAFT"), APPROVED("APPROVED"), REJECTED("REJECTED"), RETIRED("RETIRED");

    companion object {
        /** 将 HTTP 审批状态规范化为封闭状态机值。 */
        fun fromBoundary(value: String?): FeishuMappingDecision? = value?.trim()?.uppercase(Locale.ROOT)
            ?.let { code -> entries.firstOrNull { it.code == code } }
        /** 严格解析数据库审批状态，未知值禁止参与同步。 */
        fun requireStorage(value: String) = requireNotNull(entries.firstOrNull { it.code == value })
    }
}

/** 飞书同步运行状态；稳定 code 保持既有管理 API 与数据库编码。 */
enum class FeishuSyncStatus(val code: String) {
    RUNNING("RUNNING"), COMPLETED("COMPLETED"), PARTIAL_SUCCESS("PARTIAL_SUCCESS"), FAILED("FAILED");
}

/** 飞书组织事实中用户启停状态；稳定 code 保持现有数据库编码。 */
enum class FeishuOrganizationUserStatus(val code: String) {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE"),
}

/** 权限域写操作的稳定审计动作编码。 */
enum class AuthorizationAuditAction(val code: String) {
    ROLE_PERMISSIONS_REPLACED("ROLE_PERMISSIONS_REPLACED"),
    AUTH_USER_CREATED("AUTH_USER_CREATED"),
    AUTH_USER_UPDATED("AUTH_USER_UPDATED"),
    AUTH_USER_DISABLED("AUTH_USER_DISABLED"),
    USER_ROLES_ASSIGNED("USER_ROLES_ASSIGNED"),
    USER_DATA_PERMISSIONS_REPLACED("USER_DATA_PERMISSIONS_REPLACED"),
    FEISHU_ROLE_MAPPING_CREATED("FEISHU_ROLE_MAPPING_CREATED"),
    FEISHU_ROLE_MAPPING_UPDATED("FEISHU_ROLE_MAPPING_UPDATED"),
    FEISHU_ROLE_MAPPING_APPROVED("FEISHU_ROLE_MAPPING_APPROVED"),
    FEISHU_ROLE_MAPPING_REJECTED("FEISHU_ROLE_MAPPING_REJECTED"),
    FEISHU_ROLE_MAPPING_RETIRED("FEISHU_ROLE_MAPPING_RETIRED"),
    FEISHU_USER_AUTHORIZATION_SYNCHRONIZED("FEISHU_USER_AUTHORIZATION_SYNCHRONIZED");

    companion object {
        /** 将允许的审批终态映射为稳定审计动作；草稿不构成审批事件。 */
        fun forDecision(decision: FeishuMappingDecision): AuthorizationAuditAction = when (decision) {
            FeishuMappingDecision.APPROVED -> FEISHU_ROLE_MAPPING_APPROVED
            FeishuMappingDecision.REJECTED -> FEISHU_ROLE_MAPPING_REJECTED
            FeishuMappingDecision.RETIRED -> FEISHU_ROLE_MAPPING_RETIRED
            FeishuMappingDecision.DRAFT -> error("草稿不是审批动作")
        }
    }
}

/** 安全审计目标的稳定存储编码；仅在审计写入边界转回字符串。 */
enum class AuthorizationAuditTarget(val code: String) {
    AUTH_USER("AUTH_USER"),
    AUTH_ROLE("AUTH_ROLE"),
    FEISHU_ROLE_MAPPING("FEISHU_ROLE_MAPPING"),
    AUTH_ACCOUNT("AUTH_ACCOUNT"),
}

/** 审计分类的稳定编码；通用 Web 请求审计与授权审计共用现有协议值。 */
enum class AuthorizationAuditClass(val code: String) {
    SECURITY("SECURITY"),
    BUSINESS("BUSINESS"),
    QUERY("QUERY"),
}

/** 审计结果的稳定编码，不改变历史数据与下游查询语义。 */
enum class AuthorizationAuditOutcome(val code: String) {
    SUCCESS("SUCCESS"),
    DENIED("DENIED"),
    FAILED("FAILED"),
}

/** 登录链安全审计动作；[code] 保持现有 operation 编码。 */
enum class AuthenticationAuditAction(val code: String, val outcome: AuthorizationAuditOutcome) {
    LOGIN_BLOCKED("LOGIN_BLOCKED", AuthorizationAuditOutcome.DENIED),
    LOGIN_FAILED("LOGIN_FAILED", AuthorizationAuditOutcome.DENIED),
    LOGIN_SUCCESS("LOGIN_SUCCESS", AuthorizationAuditOutcome.SUCCESS),
}

/** 登录失败原因的集中兼容值，避免仓储实现重新拼写审计协议。 */
enum class AuthenticationFailureReason(val code: String) {
    INVALID_CREDENTIALS("INVALID_CREDENTIALS"),
}
