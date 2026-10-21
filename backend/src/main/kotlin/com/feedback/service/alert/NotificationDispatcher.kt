package com.feedback.service.alert

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.feedback.service.masking.ContactStorageCrypto
import com.feedback.model.po.alert.NotificationAttemptPo
import com.feedback.model.po.alert.NotificationCompletionPo
import com.feedback.model.po.alert.NotificationEnqueuePo
import com.feedback.model.po.alert.NotificationOutboxPo
import com.feedback.messaging.MqProperties
import com.feedback.repository.alert.NotificationDispatchRepository
import com.feedback.repository.alert.NotificationOutboxRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import kotlin.system.measureTimeMillis

/** 持久化发件箱。告警事务只做入队，外部 I/O 在 Worker 里发生。 */
@Service
class NotificationDispatcher(
    private val repository:NotificationDispatchRepository,
    private val mapper: ObjectMapper,
    private val outbox: NotificationOutbox,
    private val emailSender: EmailSender,
    private val metrics: NotificationMetrics,
    @Value("\${app.alert.feishu-webhook:}") private val feishuWebhook: String,
    @Value("\${app.alert.email-recipients:}") emailRecipients: String,
    @Value("\${app.alert.sms-webhook:}") private val smsWebhook: String = "",
    @Value("\${app.alert.sms-recipients:}") smsRecipientsRaw: String = "",
    @Value("\${app.alert.feishu-all-webhook:}") private val feishuAllWebhook: String = "",
    @Value("\${app.alert.feishu-dm-endpoint:}") private val feishuDmEndpoint: String = "",
    @Value("\${app.alert.feishu-task-endpoint:}") private val feishuTaskEndpoint: String = "",
    @Value("\${app.alert.feishu-api-token:}") private val feishuApiToken: String = "",
    @Value("\${app.alert.feishu-recipient-ids:}") feishuRecipientIdsRaw: String = "",
    @Value("\${app.alert.voice-call-webhook:}") private val voiceCallWebhook: String = "",
    @Value("\${app.alert.voice-call-token:}") private val voiceCallToken: String = "",
    @Value("\${app.alert.voice-call-recipients:}") voiceCallRecipientsRaw: String = "",
    private val policy: NotificationPolicy = NotificationPolicy(),
    private val contactCrypto:ContactStorageCrypto?=null
) {
    private val client = OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3)).callTimeout(Duration.ofSeconds(8)).build()
    private val renderer = AlertNotificationRenderer(mapper)
    private val recipients = emailRecipients.split(',').map(String::trim)
        .filter { it.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) }.distinct()
    private val smsRecipients = smsRecipientsRaw.split(',').map(String::trim).filter { it.matches(Regex("^\\+?[0-9]{6,20}$")) }.distinct()
    private val voiceCallRecipients = voiceCallRecipientsRaw.split(',').map(String::trim).filter { it.matches(Regex("^\\+?[0-9]{6,20}$")) }.distinct()
    private val feishuRecipientIds = feishuRecipientIdsRaw.split(',').map(String::trim).filter { it.matches(Regex("^[A-Za-z0-9_-]{2,100}$")) }.distinct()

    fun dispatch(event: AlertNotificationEvent, ruleId: Long, channels: List<String>, recipientRefs: List<String> = emptyList()) {
        val (resolvedChannels,severityPolicy)=policy.resolve(event.severity,channels)
        if(severityPolicy.mode=="DAILY_DIGEST")return
        val content = mapper.writeValueAsString(event)
        resolvedChannels.forEach { channel ->
            // 手机号与邮箱在投递时从受保护的运行时配置里解析，
            // 绝不能写进 Outbox 的 JSON。
            val channelRecipients=when(channel){"EMAIL","SMS","VOICE_CALL"->emptyList();"FEISHU_DM","FEISHU_TASK"->(recipientRefs.filter{it.startsWith("FEISHU:")}.map{it.removePrefix("FEISHU:")}+feishuRecipientIds).distinct();else->recipientRefs.distinct()}
            repository.enqueue(NotificationEnqueuePo(ruleId,event.ruleCode,event.instanceId,channel,severityPolicy.version,severityPolicy.mode,mapper.writeValueAsString(channelRecipients),content))
        }
    }

    fun deliverP3DailyDigest(){
        if(repository.digestExists())return;val count=repository.p3Yesterday()
        val event=AlertNotificationEvent(eventId="p3-digest-${java.time.LocalDate.now().minusDays(1)}",eventType="P3_DAILY_DIGEST",title="P3 告警日报",summary="昨日 P3 告警共 $count 条",severity="P3",status="DIGEST",ruleCode="P3_DAILY_DIGEST",ruleName="P3 每日汇总",currentValue=count.toDouble(),window="24h",instanceId=0,firedAt=java.time.OffsetDateTime.now().toString())
        repository.insertDigest(mapper.writeValueAsString(event))
    }

    /**
     * 轮询轨：认领一条待投递记录并投递。
     *
     * 认领（`claim` + `markSent`）与投递在同一个方法里，是因为轮询轨**没有中间人**：
     * 谁认领谁投递，认领那一刻整行就已经在手里了。
     *
     * @return false 表示队列里没有待投递记录（正常终止），不是错误。
     */
    fun deliverOne(): Boolean {
        val row = outbox.claimOne() ?: return false
        deliver(row)
        return true
    }

    /**
     * MQ 消费轨：按行号投递一条**已被中继认领**的记录。
     *
     * 中继只把行号发到主题上（正文留在库里），所以消费侧要先把那条已认领的记录读回来。
     * 读不回来不是错误：它只可能是「这条已经被投递过、状态不再是 SENT」，
     * 也就是消息重复（中继的陈旧 SENT 修复规则会重发，broker 自己也会重投）——
     * 重复在这里的处置是**确认掉**，而不是抛异常去换一次注定同样结果的重投。
     *
     * @return false 表示这条记录不在 SENT 状态（已被处理或从未存在）。
     */
    fun deliverClaimed(id: Long): Boolean {
        val row = outbox.findClaimed(id) ?: return false
        deliver(row)
        return true
    }

    /**
     * 真正干活的那一段：解码 → 按渠道投递 → 记账。
     *
     * 两条轨唯一的区别只在「行是从哪来的」，所以这里**只做投递**，不再碰发件箱的读取。
     * 末尾的 `complete` 是两条轨共用的收口：它按 `notify_status='SENT'` 做条件更新，
     * 因此重复投递不会把状态改乱，只会让那条 UPDATE 影响 0 行、由
     * `NotificationOutboxRepository` 抛成一次状态冲突——那是**真的**有两条轨同时在处理
     * 同一行时才会出现的信号，不该被静默吞掉。
     */
    private fun deliver(row: NotificationOutboxPo) {
        val channel=row.channel;val content=row.content
        val event = decode(content)
        val rowRecipients=runCatching{mapper.readTree(row.recipientsJson?:"[]").map(JsonNode::asText)}.getOrDefault(emptyList())
        if(event.eventType=="QUICK_RCA_READY"&&!sourceAlertDelivered(event,channel)){
            val result=DeliveryResult.failure("SOURCE_ALERT_NOT_DELIVERED","同渠道原告警尚未成功投递，快速RCA已阻断并等待重试")
            outbox.complete(row,result,0);metrics.record(channel,result,0);return
        }
        lateinit var result: DeliveryResult
        val duration = measureTimeMillis { result = when (channel) {
            "IN_APP" -> DeliveryResult.success(providerCode = "IN_APP")
            "FEISHU", "FEISHU_CARD" -> postJson(feishuWebhook, renderer.feishu(event), event.eventId, strictFeishu = true)
            "FEISHU_ALL" -> postJson(feishuAllWebhook, mapper.writeValueAsString(mapOf("msg_type" to "text","content" to mapOf("text" to "<at user_id=\"all\">所有人</at> [${event.severity}] ${event.title}\n${event.summary}"))), event.eventId, strictFeishu = true)
            "FEISHU_DM" -> if(rowRecipients.isNotEmpty()&&feishuApiToken.isNotBlank()) postJson(feishuDmEndpoint,mapper.writeValueAsString(mapOf("receive_id_type" to "open_id","receive_ids" to rowRecipients,"msg_type" to "text","content" to mapper.writeValueAsString(mapOf("text" to "[${event.severity}] ${event.title}\n${event.summary}")))),event.eventId,feishuApiToken,true) else DeliveryResult.failure("CONFIGURATION","未配置飞书私信收件人或API令牌")
            "FEISHU_TASK" -> if(rowRecipients.isNotEmpty()&&feishuApiToken.isNotBlank()) postJson(feishuTaskEndpoint,mapper.writeValueAsString(mapOf("summary" to "[${event.severity}] ${event.title}","description" to event.summary,"assignee_ids" to rowRecipients,"client_token" to event.eventId)),event.eventId,feishuApiToken,true) else DeliveryResult.failure("CONFIGURATION","未配置飞书任务负责人或API令牌")
            "EMAIL" -> resolveContact(row,"EMAIL").takeIf{it.isNotEmpty()}?.let{emailSender.send(renderer.email(event,it))}
                ?: if (recipients.isNotEmpty()) emailSender.send(renderer.email(event, recipients))
                else DeliveryResult.failure("CONFIGURATION", "未配置有效邮件收件人")
            "SMS" -> (resolveContact(row,"PHONE").ifEmpty{smsRecipients}).takeIf{it.isNotEmpty()}?.let{postJson(smsWebhook,mapper.writeValueAsString(mapOf("eventId" to event.eventId,"recipients" to it,"severity" to event.severity,"title" to event.title,"summary" to event.summary)),event.eventId)}
                ?: DeliveryResult.failure("CONFIGURATION","未配置有效短信收件人")
            "VOICE_CALL" -> (resolveContact(row,"PHONE").ifEmpty{voiceCallRecipients}).takeIf{it.isNotEmpty()}?.let{postJson(voiceCallWebhook,mapper.writeValueAsString(mapOf("requestId" to event.eventId,"recipients" to it,"template" to "ALERT_VOICE_V1","variables" to mapOf("severity" to event.severity,"title" to event.title,"summary" to event.summary),"ack" to mapOf("mode" to "DTMF","key" to "1"))),event.eventId,voiceCallToken)}
                ?: DeliveryResult.failure("CONFIGURATION","未配置有效电话语音收件人")
            else -> DeliveryResult.failure("UNSUPPORTED_CHANNEL", "不支持的通知渠道")
        } }
        outbox.complete(row, result, duration)
        metrics.record(channel,result,duration)
    }

    private fun sourceAlertDelivered(event:AlertNotificationEvent,channel:String)=repository.sourceDelivered(event.instanceId,normalize(channel))

    private fun decode(content: String): AlertNotificationEvent = runCatching {
        val node = mapper.readTree(content)
        if (node["schemaVersion"] == null) legacyAlertEvent(mapper, content) else mapper.treeToValue(node, AlertNotificationEvent::class.java)
    }.getOrElse { legacyAlertEvent(mapper, content) }
    private fun resolveContact(row:NotificationOutboxPo,kind:String):List<String>{
        val user=row.targetUserId?:return emptyList();val crypto=contactCrypto?:return emptyList();val r=repository.contact(user,kind)?:return emptyList()
        val value=runCatching{crypto.decrypt(r.ciphertext,r.tag,r.nonce)}.getOrNull()?:return emptyList()
        return if((kind=="EMAIL"&&value.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")))||(kind=="PHONE"&&value.matches(Regex("^\\+?[0-9]{6,20}$"))))listOf(value)else emptyList()
    }
    private fun postJson(url: String, body: String, eventId:String, bearerToken:String="", strictFeishu:Boolean=false): DeliveryResult {
        if (url.isBlank() || !url.startsWith("https://")) return DeliveryResult.failure("CONFIGURATION", "通知Webhook必须使用HTTPS")
        return try {
            val builder=Request.Builder().url(url).header("Idempotency-Key",eventId).post(body.toRequestBody("application/json".toMediaType()));if(bearerToken.isNotBlank())builder.header("Authorization","Bearer $bearerToken")
            client.newCall(builder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty().take(4096)
                val providerCode = runCatching { mapper.readTree(responseBody).let { it["code"] ?: it["StatusCode"] }?.asText() }.getOrNull()
                if (response.isSuccessful && (!strictFeishu || providerCode == null || providerCode == "0")) DeliveryResult.success(response.code, providerCode ?: "HTTP_ACCEPTED",response.header("X-Request-Id"))
                else DeliveryResult.failure(httpError(response.code), "飞书网关返回HTTP ${response.code}", response.code, providerCode)
            }
        } catch (error: Exception) {
            val type = networkError(error)
            DeliveryResult.failure(type, safeSummary(type))
        }
    }
    private fun normalize(raw: String) = policy.normalize(raw)
    companion object{val CHANNELS=setOf("FEISHU_CARD","FEISHU_ALL","FEISHU_DM","FEISHU_TASK","SMS","VOICE_CALL","EMAIL","IN_APP")}
}

/**
 * 通知投递的调度入口。
 *
 * ## MQ 模式下**整轨空转**
 *
 * `app.mq.enabled=true` 时本方法立刻返回，一条也不认领：认领动作本身会把记录标成 SENT，
 * 于是「两条轨都在认领」的结果是**两边各投递一半**——同一条通知不是重复，而是漏投，
 * 因为轮询轨认领之后由 MQ 轨发出去的假设并不成立。所以这里不是「少做一点」，
 * 而是必须**完全不做**。
 *
 * 空转不等于投递停了：投递由 `NotificationMqTrack` 驱动（中继发号、消费者按号投递）。
 * 启动时 `MqTrackReporter` 会打一行说明当前走哪条轨，排障从那一行看起。
 *
 * ## 为什么这个判断放在这里，而不是让 XXL 别调度它
 *
 * 调度器与开关是两层：`xxl.job.enabled` 管「调度平台会不会触发」，
 * 本判断管「触发了也不该做」。两层都关才真正停跑；只有一层时另一层仍然有效。
 * 这也是 `job/AlertJobHandlers.kt` 里的 handler 保持原样的原因——它是薄适配器，
 * 开关不该长在它身上。
 */
@Component
class NotificationWorkerScheduler(
    private val dispatcher: NotificationDispatcher,
    private val properties: MqProperties
) {
    fun scheduledDeliver() {
        if (properties.enabled) return
        repeat(20) { if (!dispatcher.deliverOne()) return }
    }
}

@Service
class NotificationOutbox(private val repository:NotificationOutboxRepository,private val escalation:NotificationEscalationService?=null) {
    @org.springframework.transaction.annotation.Transactional
    fun claimOne():NotificationOutboxPo?=repository.claim()

    /**
     * 按行号读回一条**已被认领**的记录，供 MQ 消费轨使用（见 `NotificationMqTrack`）。
     *
     * **不带事务**，与 [claimOne] 不同：认领要「加锁取候选 + 标记 SENT」原子完成，
     * 而这里只是一次读，读到的行在整个投递过程中不会再被本轨修改状态——
     * 状态变更发生在 [complete] 里，那一步自带事务与 `notify_status='SENT'` 条件。
     * 给它加事务只会把一次读拖进一个跨外部 I/O 的长事务（投递在事务外发生，
     * 但读与投递之间的窗口会被无谓地拉长）。
     */
    fun findClaimed(id:Long):NotificationOutboxPo?=repository.findClaimed(id)

    @org.springframework.transaction.annotation.Transactional
    fun complete(row:NotificationOutboxPo,result:DeliveryResult,durationMs:Long){
        val cycleAttempt=row.retryCount+1
        val attempt=repository.nextAttempt(row.id)
        repository.record(NotificationAttemptPo(row.id,row.alertInstanceId,attempt,row.channel,if(result.delivered)"DELIVERED" else "FAILED",durationMs,result.httpStatus,result.providerCode,result.providerMessageId,result.errorType,result.errorSummary,durationMs*1000))
        val completion=NotificationCompletionPo(row.id,result.httpStatus,result.providerCode,result.providerMessageId,result.errorType,result.errorSummary,cycleAttempt,1L shl cycleAttempt.coerceAtMost(20))
        if(result.delivered)repository.delivered(completion)
        else if(cycleAttempt>=row.maxRetries){
            repository.deadLetter(completion,row.channel)
            escalation?.notificationFailed(row.id,result.errorSummary?:result.errorType?:"投递失败")
        }else repository.failed(completion)
    }
}
