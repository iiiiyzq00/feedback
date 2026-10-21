package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.AbstractException
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.Jira400Properties
import com.feedback.model.po.attachment.FeedbackAttachmentPo
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.repository.jira.JiraFeedbackSourceRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.SocketTimeoutException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

data class JiraLogAttachmentImportResult(
    val requestedIssues:Int,
    val matchedIssues:Int,
    val importedFiles:Int,
    val skippedFiles:Int,
    val failedFiles:Int,
    val failureCodes:Map<String,Int>,
    val hasRetryableFailure:Boolean=false
)

@Service
class JiraLogAttachmentImportService(
    private val properties:Jira400Properties,
    private val gateway:Jira400Gateway,
    private val sources:JiraFeedbackSourceRepository,
    private val attachments:FeedbackAttachmentMutationRepository,
    @Value("\${app.attachment.dir:data/attachments}") attachmentDir:String
) {
    private val root=Path.of(attachmentDir).toAbsolutePath().normalize()
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds.coerceIn(1,30)))
        .readTimeout(Duration.ofSeconds(120)).callTimeout(Duration.ofSeconds(180)).build()
    private val logMime=setOf("text/plain","application/zip","application/gzip","application/x-7z-compressed","application/x-rar-compressed","application/x-tar")
    private val imageMime=setOf("image/jpeg","image/png","image/webp")
    private val videoMime=setOf("video/mp4","video/quicktime","video/webm")
    private val logName=Regex("(?i).*(?:logcat|日志|\\.(?:log|txt|zip|gz|7z|rar|tar))$")
    private val imageName=Regex("(?i).*\\.(?:jpg|jpeg|png|webp)$")
    private val videoName=Regex("(?i).*\\.(?:mp4|mov|webm)$")
    private val maxBytes=100L*1024*1024

    fun ready()=properties.ready()

    fun pull(issueKeys:List<String>):JiraLogAttachmentImportResult {
        if(!properties.ready()) throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_CONFIGURATION_NOT_READY")
        val keys=issueKeys.map{it.trim().uppercase()}.distinct()
        if(keys.isEmpty()||keys.size>20||keys.any{!it.matches(Regex("^[A-Z][A-Z0-9]+-[0-9]+$"))})
            throw ClientException(ErrorCode.BAD_REQUEST,"JIRA_LOG_KEYS_INVALID")
        val jql="(${properties.jql}) AND key IN (${keys.joinToString(","){"'$it'"}}) ORDER BY id ASC"
        val page=gateway.search(jql,0,20,listOf("attachment"))
        var imported=0;var skipped=0;var failed=0;var matched=0;var hasRetryableFailure=false
        val failureCodes=linkedMapOf<String,Int>()
        page.issues.forEach { issue ->
            val sourceId=sources.findSourceId(issue.key)
            if(sourceId==null)return@forEach
            val candidates=issue.fields.path("attachment").filter { attachmentType(it)!=null }
            if(candidates.isNotEmpty())matched++
            candidates.forEach { node ->
                runCatching { download(sourceId,issue.key,node) }.fold(
                    onSuccess={if(it)imported++ else skipped++},onFailure={error->
                        failed++
                        val governed=error as? AbstractException
                        val code=governed?.reasonCode?:governed?.errorCode?.name
                            ?:error.message?.takeIf{it.matches(Regex("^[A-Z0-9_]+$"))}
                            ?:error.javaClass.simpleName
                        hasRetryableFailure=hasRetryableFailure||(governed?.errorCode?.retryable?:false)
                        failureCodes[code]=(failureCodes[code]?:0)+1
                    }
                )
            }
        }
        val fetched=page.issues.map{it.key.uppercase()}.toSet()
        val missing=keys.count{it !in fetched}
        if(missing>0){failed+=missing;failureCodes["JIRA_ATTACHMENT_ISSUE_NOT_FOUND"]=(failureCodes["JIRA_ATTACHMENT_ISSUE_NOT_FOUND"]?:0)+missing}
        return JiraLogAttachmentImportResult(keys.size,matched,imported,skipped,failed,failureCodes,hasRetryableFailure)
    }

    private fun download(sourceId:Long,issueKey:String,node:com.fasterxml.jackson.databind.JsonNode):Boolean {
        val jiraAttachmentId=node.path("id").asText().takeIf(String::isNotBlank)?.take(100)
            ?:throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_ATTACHMENT_ID_MISSING")
        attachments.findSourceIdentity("JIRA",jiraAttachmentId)?.let{return false}
        val url=node.path("content").asText().takeIf(String::isNotBlank)
            ?:throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_ATTACHMENT_URL_MISSING")
        val uri=URI(url);val base=URI(properties.baseUrl)
        if(uri.scheme!="https"||!uri.host.equals(base.host,true)||uri.userInfo!=null||uri.fragment!=null)
            throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_ATTACHMENT_URL_INVALID")
        val declaredSize=node.path("size").asLong(-1)
        if(declaredSize !in 1..maxBytes) throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_LOG_ATTACHMENT_SIZE_INVALID")
        val dir=root.resolve("jira").resolve(issueKey).normalize()
        if(!dir.startsWith(root)) throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_ATTACHMENT_STORAGE_PATH_INVALID")
        Files.createDirectories(dir)
        val temp=Files.createTempFile(dir,"download-",".part")
        try {
            val request=Request.Builder().url(url).header(properties.authHeader,authorizationValue()).header("Accept","application/octet-stream").get().build()
            executeDownload(request) { response ->
                val body=response.body?:throw RemoteException(
                    ErrorCode.JIRA_RESPONSE_INVALID,
                    "JIRA_ATTACHMENT_EMPTY_RESPONSE"
                )
                val digest=MessageDigest.getInstance("SHA-256");var total=0L
                body.byteStream().use { input -> Files.newOutputStream(temp).use { output ->
                    val buffer=ByteArray(DEFAULT_BUFFER_SIZE);while(true){
                        val read=try{input.read(buffer)}catch(error:IOException){
                            throw RemoteException(ErrorCode.JIRA_UNAVAILABLE,"JIRA_ATTACHMENT_STREAM_FAILED",cause=error)
                        }
                        if(read<0)break;total+=read
                        if(total>maxBytes) throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_LOG_ATTACHMENT_TOO_LARGE")
                        digest.update(buffer,0,read)
                        try{output.write(buffer,0,read)}catch(error:IOException){
                            throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_ATTACHMENT_STORAGE_WRITE_FAILED",cause=error)
                        }
                    };buffer.fill(0)
                }}
                if(total<=0||total!=declaredSize) throw RemoteException(ErrorCode.JIRA_RESPONSE_INVALID,"JIRA_LOG_ATTACHMENT_SIZE_MISMATCH")
                val hash=digest.digest()
                attachments.findSourceHash(sourceId,hash,"JIRA")?.let{return false}
                val mimeType=resolvedMimeType(node,temp)
                val target=dir.resolve(hash.joinToString(""){"%02x".format(it)}+".quarantine").normalize()
                if(!target.startsWith(dir)) throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_ATTACHMENT_STORAGE_PATH_INVALID")
                // 上一次数据库故障可能让这个按内容寻址的
                // 隔离文件没能留下权威的元数据行。在上面那次
                // 数据库去重检查之后，只替换这条
                // 已解析的哈希路径是安全的。
                Files.deleteIfExists(target)
                Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)
                try {
                    val type=attachmentType(node)
                        ?:throw ServiceException(ErrorCode.INTERNAL_ERROR,"JIRA_ATTACHMENT_TYPE_MISSING")
                    attachments.create(FeedbackAttachmentPo(publicId=uuidBytes(UUID.randomUUID()),sourceId=sourceId,attachmentType=type,
                        mimeType=mimeType,fileSizeBytes=total,fileSha256=hash,
                        storagePath=target.toString(),storageKeyId="jira-quarantine-v1",
                        // client_attachment_id 仍是 SDK 摄入那一对键；
                        // Jira 的外部身份有自己的受治理字段。
                        clientAttachmentId=null,sourceAttachmentId=jiraAttachmentId,
                        uploadStatus="VERIFIED",integrityStatus="VERIFIED",
                        decryptionStatus="NOT_REQUIRED",securityStatus="QUARANTINED",sourceKind="JIRA",
                        retentionUntil=LocalDateTime.now().plusDays(90)))
                } catch (error:Exception) {
                    Files.deleteIfExists(target)
                    throw error
                }
                return true
            }
        } finally { Files.deleteIfExists(temp) }
    }
    private inline fun <T> executeDownload(request:Request,body:(okhttp3.Response)->T):T {
        val response=try {
            client.newCall(request).execute()
        } catch(error:SocketTimeoutException) {
            throw RemoteException(ErrorCode.REMOTE_TIMEOUT,"JIRA_ATTACHMENT_TIMEOUT",cause=error)
        } catch(error:IOException) {
            throw RemoteException(ErrorCode.JIRA_UNAVAILABLE,"JIRA_ATTACHMENT_IO_FAILED",cause=error)
        }
        response.use {
                when(response.code) {
                    401->throw RemoteException(ErrorCode.JIRA_AUTHENTICATION_FAILED,"JIRA_AUTHENTICATION_FAILED")
                    403->throw RemoteException(ErrorCode.JIRA_ACCESS_FORBIDDEN,"JIRA_ACCESS_FORBIDDEN")
                    408,504->throw RemoteException(ErrorCode.REMOTE_TIMEOUT,"JIRA_ATTACHMENT_TIMEOUT")
                    429->throw RemoteException(ErrorCode.JIRA_RATE_LIMITED,"JIRA_RATE_LIMITED")
                    in 400..499->throw RemoteException(
                        ErrorCode.JIRA_REQUEST_REJECTED,
                        "JIRA_ATTACHMENT_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                    in 500..599->throw RemoteException(
                        ErrorCode.JIRA_UNAVAILABLE,
                        "JIRA_ATTACHMENT_HTTP_${response.code}",
                        mapOf("upstreamStatus" to response.code)
                    )
                }
                if(!response.isSuccessful)throw RemoteException(
                    ErrorCode.JIRA_UNAVAILABLE,
                    "JIRA_ATTACHMENT_HTTP_UNEXPECTED",
                    mapOf("upstreamStatus" to response.code)
                )
                return body(response)
        }
    }

    private fun authorizationValue()=when(properties.authType.uppercase()){
        "BASIC"->"Basic ${properties.token}";"TOKEN"->properties.tokenPrefix+properties.token;else->properties.tokenPrefix+properties.token
    }
    internal fun attachmentType(node:com.fasterxml.jackson.databind.JsonNode):String? {
        val name=node.path("filename").asText()
        val mime=node.path("mimeType").asText().substringBefore(';').lowercase()
        return when { imageName.matches(name)||mime in imageMime->"IMAGE";videoName.matches(name)||mime in videoMime->"VIDEO";logName.matches(name)||mime in logMime->"LOG";else->null }
    }
    internal fun resolvedMimeType(node:com.fasterxml.jackson.databind.JsonNode,path:Path):String? {
        val declared=node.path("mimeType").asText().substringBefore(';').trim().lowercase().takeIf(String::isNotBlank)
        if(declared!=null)return declared
        val header=ByteArray(12)
        val read=Files.newInputStream(path).use{it.read(header)}
        return when {
            read>=3&&header[0]==0xff.toByte()&&header[1]==0xd8.toByte()&&header[2]==0xff.toByte()->"image/jpeg"
            read>=8&&header.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))->"image/png"
            read>=12&&header.sliceArray(0..3).contentEquals("RIFF".toByteArray())&&header.sliceArray(8..11).contentEquals("WEBP".toByteArray())->"image/webp"
            read>=12&&header.sliceArray(4..7).contentEquals("ftyp".toByteArray())->"video/mp4"
            read>=4&&header.sliceArray(0..3).contentEquals(byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte()))->"video/webm"
            else->null
        }
    }
    private fun uuidBytes(uuid:UUID)=ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}
