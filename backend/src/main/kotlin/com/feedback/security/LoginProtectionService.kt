package com.feedback.security

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.repository.auth.LoginAuditRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration

/** 登录失败计数存储；实现可以使用 Redis，也可以在测试中使用内存替身。 */
interface LoginAttemptStore {
    /** 读取当前限制窗口内的失败次数。 */
    fun count(key: String): Long
    /** 原子增加失败次数，并在首次写入时设置窗口有效期。 */
    fun increment(key: String, ttl: Duration): Long
    /** 成功登录后清除对应账号的失败计数。 */
    fun reset(key: String)
}

/** 用 Redis 原子脚本实现固定窗口登录失败计数。 */
@Component
class RedisLoginAttemptStore(private val redis: StringRedisTemplate) : LoginAttemptStore {
    private val incrementWithExpiry = DefaultRedisScript(
        "local c=redis.call('INCR',KEYS[1]); if c==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return c",
        Long::class.java
    )
    /** 读取 Redis 固定窗口计数；缺失或非数字值按零处理。 */
    override fun count(key: String): Long = redis.opsForValue().get(key)?.toLongOrNull() ?: 0

    /** 通过 Lua 原子递增并仅在首次写入设置 TTL；Redis 不可用时失败关闭。 */
    override fun increment(key: String, ttl: Duration): Long {
        return redis.execute(incrementWithExpiry, listOf(key), ttl.seconds.toString())
            ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "LOGIN_RATE_LIMIT_REDIS_UNAVAILABLE")
    }

    /** 删除账号维度计数；Redis 调用不加入数据库登录事务。 */
    override fun reset(key: String) {
        redis.delete(key)
    }
}

/** 认证入口所需的登录保护能力。 */
interface LoginProtection {
    /** 在密码校验前检查账号和 IP 是否已达到限制。 */
    fun assertAllowed(username: String, clientIp: String)
    /** 密码校验失败后分别累加账号与 IP 计数并留下安全审计。 */
    fun recordFailure(username: String, clientIp: String)
    /** 成功登录后只清除账号计数；成功数据库审计由本地登录事务写入。 */
    fun resetAfterSuccess(username: String)
}

/**
 * 基于账号与来源 IP 的登录保护服务。
 *
 * 计数位于 Redis，审计记录位于数据库，两者无法由本地数据库事务保证原子性。因此这里不声明
 * `@Transactional`：审计用于追踪安全事件，不应让数据库事务跨越 Redis 网络调用。
 */
@Service
class LoginProtectionService(
    private val store: LoginAttemptStore,
    private val audits: LoginAuditRepository,
    @Value("\${app.auth.login-protection.account-limit:5}") accountLimit: Long,
    @Value("\${app.auth.login-protection.ip-limit:20}") ipLimit: Long,
    @Value("\${app.auth.login-protection.window-seconds:900}") windowSeconds: Long
) : LoginProtection {
    private val accountLimit = accountLimit.coerceIn(3, 20)
    private val ipLimit = ipLimit.coerceIn(5, 200)
    private val window = Duration.ofSeconds(windowSeconds.coerceIn(60, 86_400))

    /** 任一维度达到阈值即失败关闭，避免攻击者轮换账号或来源绕过单维限制。 */
    override fun assertAllowed(username: String, clientIp: String) {
        val accountBlocked = store.count(accountKey(username)) >= accountLimit
        val ipBlocked = store.count(ipKey(clientIp)) >= ipLimit
        if (accountBlocked || ipBlocked) {
            audits.recordBlocked(username, clientIp, accountBlocked, ipBlocked, window.seconds)
            throw ClientException(ErrorCode.AUTH_LOGIN_RATE_LIMITED, "AUTH_LOGIN_RATE_LIMITED")
        }
    }

    /** 原子累加两个限制维度，并把本次计数写入审计证据。 */
    override fun recordFailure(username: String, clientIp: String) {
        val accountCount = store.increment(accountKey(username), window)
        val ipCount = store.increment(ipKey(clientIp), window)
        audits.recordFailure(username, clientIp, accountCount, ipCount, window.seconds)
    }

    /** 只重置已验证账号的计数；IP 计数保留用于继续抵御同源撞库。 */
    override fun resetAfterSuccess(username: String) {
        store.reset(accountKey(username))
    }

    /** Redis key 使用指纹而非账号/IP 明文，降低运维侧键扫描造成的信息暴露。 */
    private fun accountKey(username: String) = "auth:login:account:${fingerprint(username.trim().lowercase())}"

    /** 使用 IP 指纹构造限流键，不在 Redis key 中暴露来源地址。 */
    private fun ipKey(ip: String) = "auth:login:ip:${fingerprint(ip)}"

    /** 生成不可逆截断 SHA-256 指纹，兼顾限流关联和最小化敏感数据。 */
    private fun fingerprint(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
}
