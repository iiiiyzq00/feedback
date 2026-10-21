package com.feedback.manager.sdk

import com.feedback.service.sdk.*

import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * 回收附件移动成功、数据库提交前进程崩溃所留下的孤儿文件。
 *
 * 清理器只识别新版 SDK 唯一文件名和 SDK 专用临时文件，并跳过保护窗口内的文件。
 * 永久文件删除前再次查询数据库权威记录；不处理已发布分块或历史固定文件名，避免误删续传数据。
 */
@Component
class SdkAttachmentOrphanCleaner(
    private val attachments: FeedbackAttachmentMutationRepository,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir: String,
    @Value("\${app.attachment.orphan-min-age-minutes:60}") minAgeMinutes: Long,
    @Value("\${app.attachment.orphan-max-files-per-run:1000}") maxFilesPerRun: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val attachmentRoot = Path.of(attachmentDir).toAbsolutePath().normalize()
    private val sdkRoot = attachmentRoot.resolve("sdk")
    private val minAge = Duration.ofMinutes(minAgeMinutes.coerceIn(10, 24 * 60))
    private val maxFiles = maxFilesPerRun.coerceIn(1, 10_000)

    /** 在有界目录内清理过期 SDK 临时文件；先与数据库登记路径交叉核对，拒绝越界删除。 */
    fun clean() {
        if (!Files.isDirectory(attachmentRoot)) {
            return
        }

        val cutoff = Instant.now().minus(minAge)
        val candidates = Files.walk(attachmentRoot).use { paths ->
            paths.filter { path ->
                isExpiredManagedFile(path, cutoff)
            }.limit(maxFiles).toList()
        }
        val registeredPaths = registeredPaths(candidates) ?: return
        val deleted = candidates.count { deleteCandidate(it, registeredPaths) }
        if (deleted > 0) {
            log.warn("SDK attachment orphan cleanup completed: inspected={} deleted={}", candidates.size, deleted)
        }
    }

    /** 分页加载数据库已登记的 SDK 附件路径并规范化；设置总量上限，防止清理任务形成无界内存集合。 */
    private fun registeredPaths(candidates: List<Path>): Set<String>? {
        val permanentPaths = candidates
            .filterNot(::isSdkTemporaryFile)
            .map { it.toAbsolutePath().normalize().toString() }
        if (permanentPaths.isEmpty()) {
            return emptySet()
        }
        return try {
            attachments.registeredStoragePaths(permanentPaths)
        } catch (ex: Exception) {
            // 数据库不可用时失败关闭：宁可延迟清理，也不猜测文件是否已登记。
            log.error("SDK attachment orphan cleanup skipped because registry lookup failed", ex)
            null
        }
    }

    /** 确认候选文件属于受控目录、未被数据库引用且超过保留期；任一条件不满足都禁止删除。 */
    private fun isExpiredManagedFile(path: Path, cutoff: Instant): Boolean = try {
        Files.isRegularFile(path) &&
            isManagedFile(path) &&
            !Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)
    } catch (ex: Exception) {
        log.warn("SDK attachment orphan cleanup could not inspect file: path={}", path, ex)
        false
    }

    /** 删除单个已确认的孤儿文件；仅记录安全路径标识和异常类型，不泄露文件内容。 */
    private fun deleteCandidate(path: Path, registeredPaths: Set<String>): Boolean {
        return try {
            val normalized = path.toAbsolutePath().normalize()
            if (!normalized.startsWith(attachmentRoot)) {
                return false
            }
            if (isSdkTemporaryFile(normalized)) {
                return Files.deleteIfExists(normalized)
            }
            if (!normalized.startsWith(sdkRoot)) {
                return false
            }
            if (normalized.toString() in registeredPaths) {
                return false
            }
            Files.deleteIfExists(normalized)
        } catch (ex: Exception) {
            log.warn("SDK attachment orphan cleanup skipped file: path={}", path, ex)
            false
        }
    }

    /** 验证候选路径规范化后仍位于 SDK 附件根目录，阻断目录穿越和越界清理。 */
    private fun isManagedFile(path: Path): Boolean =
        SDK_STORED_FILE.matches(path.fileName.toString()) || isSdkTemporaryFile(path)

    /** 识别仅由 SDK 上传流程创建的临时文件后缀，避免清理其他业务文件。 */
    private fun isSdkTemporaryFile(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        val name = normalized.fileName.toString()
        val rootTemporary = normalized.parent == attachmentRoot && SDK_ROOT_TEMPORARY_FILE.matches(name)
        val chunkTemporary = SDK_CHUNK_TEMPORARY_FILE.matches(name) &&
            normalized.any { segment -> segment.toString() == ".chunks" }
        return rootTemporary || chunkTemporary
    }

    private companion object {
        val SDK_STORED_FILE = Regex(
            "^[A-Za-z0-9_-]{1,50}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin$"
        )
        val SDK_ROOT_TEMPORARY_FILE = Regex("^sdk-(upload-.+\\.part|assembled-.+\\.enc|plain-.+\\.part)$")
        val SDK_CHUNK_TEMPORARY_FILE = Regex("^chunk-.+\\.pending$")
    }
}
