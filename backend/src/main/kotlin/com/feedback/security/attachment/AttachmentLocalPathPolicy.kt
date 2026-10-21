package com.feedback.security.attachment

import com.feedback.service.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * 本地附件路径的统一信任边界。
 *
 * `attachment.dir` 是当前写入根；历史只读根必须通过配置显式列入白名单。数据库中的绝对路径只有位于
 * 任一白名单根、指向普通文件且不是符号链接时才可读取或迁移，避免因工作区搬迁而放宽为任意路径。
 */
@Component
class AttachmentLocalPathPolicy(
    @Value("\${app.attachment.dir:data/attachments}") currentRoot: String,
    @Value("\${app.attachment.legacy-read-roots:}") legacyReadRoots: String
) {
    private val allowedRoots: List<Path> = buildList {
        add(normalizeRoot(currentRoot))
        legacyReadRoots.split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapTo(this, ::normalizeRoot)
    }.distinct()
    private val trustedRoots: List<Path> = allowedRoots.map { root ->
        runCatching { root.toRealPath() }.getOrDefault(root)
    }.distinct()

    /** 解析已存在普通文件的可信路径；任何格式、符号链接或越界异常都返回 null。 */
    fun readablePath(raw: String): Path? {
        val path = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: return null
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            return null
        }
        // 末级符号链接已显式拒绝；这里跟随并规范化父目录别名（macOS 的 /var -> /private/var），
        // 保证文件真实路径与初始化时已 realpath 的白名单根使用同一比较口径。
        val realPath = runCatching { path.toRealPath() }.getOrNull() ?: return null
        // 同时校验真实路径，阻止白名单目录中的父级符号链接跳转到目录之外。
        if (trustedRoots.none(realPath::startsWith)) {
            return null
        }
        // 返回数据库路径的规范化词法形式，避免把 /var 等合法平台别名改写后破坏调用方的精确审计事实。
        return path
    }

    /** 解析可信可读路径，失败时转换为稳定服务错误。 */
    fun requireReadablePath(raw: String): Path =
        readablePath(raw) ?: unavailable("ATTACHMENT_LOCAL_SOURCE_INVALID")

    /**
     * 仅用于迁移失败分类：规范化路径必须字面位于显式白名单根内，且目标确实不存在。若白名单根仍
     * 存在，则继续执行真实父路径校验；整个历史根已被清理时只允许记录失败，绝不把结果用于读取或删除。
     */
    fun isAllowedMissingPath(raw: String): Boolean {
        val path = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull() ?: return false
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        if (allowedRoots.none(path::startsWith) && trustedRoots.none(path::startsWith)) {
            return false
        }
        return allowedRoots.none { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ||
            runCatching { requireAllowedPath(raw) }.isSuccess
    }

    /**
     * 在显式附件根内解析数据库路径，但不要求末级文件仍存在。仅用于恢复“精确删除已完成、清路径 CAS
     * 尚未提交”的崩溃窗口；调用方禁止把结果用于递归或通配符删除。
     */
    fun requireAllowedPath(raw: String): Path {
        val path = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
            ?: unavailable("ATTACHMENT_LOCAL_PATH_INVALID")
        ensure(allowedRoots.any(path::startsWith) || trustedRoots.any(path::startsWith),
            "ATTACHMENT_LOCAL_PATH_OUTSIDE_ALLOWED_ROOT")
        val existingParent = generateSequence(path.parent) { it.parent }.firstOrNull(Files::exists)
            ?: unavailable("ATTACHMENT_LOCAL_PATH_PARENT_MISSING")
        val realParent = existingParent.toRealPath()
        ensure(trustedRoots.any(realParent::startsWith), "ATTACHMENT_LOCAL_PATH_PARENT_OUTSIDE_ALLOWED_ROOT")
        return path
    }

    /** 将配置根转换为绝对规范路径，空根直接拒绝。 */
    private fun normalizeRoot(raw: String): Path {
        ensure(raw.isNotBlank(), "ATTACHMENT_LOCAL_ROOT_BLANK")
        return Path.of(raw).toAbsolutePath().normalize()
    }

    /** 统一执行路径不变量校验，失败时关闭访问。 */
    private fun ensure(condition: Boolean, reasonCode: String) {
        if (!condition) {
            unavailable(reasonCode)
        }
    }

    /** 构造稳定的附件服务不可用错误。 */
    private fun unavailable(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.ATTACHMENT_SERVICE_UNAVAILABLE, reasonCode)
}
