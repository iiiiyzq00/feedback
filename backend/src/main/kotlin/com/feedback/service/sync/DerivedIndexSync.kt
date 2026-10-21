package com.feedback.service.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.messaging.MqProperties
import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.JsonData
import com.feedback.repository.sync.ClaimedIndexTask
import com.feedback.repository.sync.DerivedIndexRepository
import com.feedback.repository.sync.IndexTaskWriteOutcome
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.milvus.v2.client.ConnectConfig
import io.milvus.v2.client.MilvusClientV2
import io.milvus.v2.common.DataType
import io.milvus.v2.service.collection.request.CreateCollectionReq
import io.milvus.v2.service.collection.request.HasCollectionReq
import io.milvus.v2.service.vector.request.DeleteReq
import io.milvus.v2.service.vector.request.SearchReq
import io.milvus.v2.service.vector.request.UpsertReq
import io.milvus.v2.service.vector.request.data.FloatVec
import io.milvus.client.MilvusServiceClient
import io.milvus.param.ConnectParam
import io.milvus.param.R
import io.milvus.param.collection.FlushParam
import io.milvus.param.MetricType
import io.milvus.param.dml.SearchParam
import io.milvus.response.SearchResultsWrapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import kotlin.math.sqrt

data class SearchDocument(
    val sourceId: Long, val channel: String, val title: String?, val description: String?,
    val functionModule: String?, val category: String?, val severity: String?, val summary: String?
)

data class SearchCandidate(val sourceId: Long, val score: Double)
interface SearchIndexGateway {
    fun upsert(document: SearchDocument): String
    fun delete(sourceId: Long)
    fun search(query: String, topK: Int, sourceIds: Set<Long> = emptySet(), excludeSourceId: Long? = null): List<SearchCandidate>
}
data class VectorCandidate(val sourceId: Long, val score: Float)
data class VectorSearchFilter(
    val sourceIds: Set<Long> = emptySet(),
    val excludeSourceId: Long? = null
)

interface VectorIndexGateway {
    val available: Boolean get() = true
    fun upsert(sourceId: Long, vector: List<Float>): String
    fun search(vector: List<Float>, topK: Int, filter: VectorSearchFilter = VectorSearchFilter()): List<VectorCandidate>
    fun delete(sourceId: Long)
    fun rebuild(vectors: Map<Long, List<Float>>)
}
interface EmbeddingProvider {
    val modelVersion: String
    val dimension: Int
    fun embed(text: String): List<Float>
}

@Component
class ElasticsearchGateway(
    clients: ObjectProvider<ElasticsearchClient>,
    @Value("\${app.elasticsearch.index-name:feedback-v1}") private val indexName: String
) : SearchIndexGateway {
    private val client = clients.ifAvailable
    override fun upsert(document: SearchDocument): String {
        val available = client ?: unavailable("ELASTICSEARCH_CLIENT_UNAVAILABLE")
        val id = document.sourceId.toString()
        available.index { request -> request.index(indexName).id(id).document(document) }
        return id
    }
    override fun delete(sourceId: Long) {
        val available=client ?: unavailable("ELASTICSEARCH_CLIENT_UNAVAILABLE")
        available.delete { it.index(indexName).id(sourceId.toString()) }
    }

    override fun search(query: String, topK: Int, sourceIds: Set<Long>, excludeSourceId: Long?): List<SearchCandidate> {
        if(query.isBlank()) invalid("ELASTICSEARCH_QUERY_EMPTY")
        if(topK !in 1..1000) invalid("ELASTICSEARCH_TOP_K_INVALID")
        val available = client ?: unavailable("ELASTICSEARCH_CLIENT_UNAVAILABLE")
        val response = available.search({ request -> request
            .index(indexName)
            .size((topK * 3).coerceAtMost(1000))
            .query { q -> q.multiMatch { m -> m.query(query)
                .fields("title^3", "summary^2", "description", "functionModule", "category") } }
        }, JsonData::class.java)
        return response.hits().hits().asSequence()
            .mapNotNull { hit -> hit.id()?.toLongOrNull()?.let { SearchCandidate(it, hit.score() ?: 0.0) } }
            .filter { sourceIds.isEmpty() || it.sourceId in sourceIds }
            .filter { it.sourceId != excludeSourceId }
            .take(topK).toList()
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun unavailable(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}

/**
 * Day 4 的可重建向量派生链路。默认哈希嵌入只用于本地联调；生产必须配置 BGE-M3 provider。
 */
@Component
@ConditionalOnProperty(prefix = "app.ai.embedding", name = ["provider"], havingValue = "hashing", matchIfMissing = true)
class HashingEmbeddingProvider(
    @Value("\${app.ai.embedding.dimension:1024}") override val dimension: Int
) : EmbeddingProvider {
    override val modelVersion = "hashing-dev-v1"
    override fun embed(text: String): List<Float> {
        val values = FloatArray(dimension)
        Regex("[\\p{L}\\p{N}_]+").findAll(text.lowercase()).forEach { match ->
            val hash = match.value.hashCode()
            val index = (hash.toLong() and 0x7fffffff).rem(dimension).toInt()
            values[index] += if ((hash and 1) == 0) 1f else -1f
        }
        val norm = sqrt(values.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return values.map { it / norm }
    }
}

@Component
@ConditionalOnProperty(prefix = "app.milvus", name = ["enabled"], havingValue = "true")
class MilvusGateway(
    @Value("\${app.milvus.host}") host: String,
    @Value("\${app.milvus.port}") port: Int,
    @Value("\${app.milvus.collection-name}") private val collection: String,
    @Value("\${app.milvus.dimension:1024}") private val dimension: Int
) : VectorIndexGateway, AutoCloseable {
    private val client = MilvusClientV2(ConnectConfig.builder().uri("http://$host:$port").build())
    private val maintenanceClient = MilvusServiceClient(ConnectParam.newBuilder().withHost(host).withPort(port).build())

    init {
        if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
            client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .dimension(dimension)
                .primaryFieldName("source_id")
                .idType(DataType.Int64)
                .vectorFieldName("embedding")
                .metricType("COSINE")
                .autoID(false)
                .build())
        }
    }

    override fun upsert(sourceId: Long, vector: List<Float>): String {
        if(vector.size != dimension) invalid("MILVUS_VECTOR_DIMENSION_INVALID")
        client.upsert(UpsertReq.builder().collectionName(collection).data(listOf(row(sourceId, vector))).build())
        return sourceId.toString()
    }

    override fun search(vector: List<Float>, topK: Int, filter: VectorSearchFilter): List<VectorCandidate> {
        if(vector.size != dimension) invalid("MILVUS_VECTOR_DIMENSION_INVALID")
        if(topK !in 1..1000) invalid("MILVUS_TOP_K_INVALID")
        val request = SearchParam.newBuilder()
            .withCollectionName(collection)
            .withVectorFieldName("embedding")
            .withFloatVectors(listOf(vector))
            .withMetricType(MetricType.COSINE)
            .withTopK(topK)
            .withOutFields(listOf("source_id"))
        filter.expression().takeIf(String::isNotBlank)?.let(request::withExpr)
        val response = maintenanceClient.search(request.build())
        if(response.status != R.Status.Success.code) unavailable("MILVUS_SEARCH_FAILED")
        return SearchResultsWrapper(response.data.results).getIDScore(0).map {
            VectorCandidate(it.longID, it.score)
        }
    }

    override fun delete(sourceId: Long) {
        client.delete(DeleteReq.builder().collectionName(collection).filter("source_id == $sourceId").build())
    }

    override fun rebuild(vectors: Map<Long, List<Float>>) {
        client.delete(DeleteReq.builder().collectionName(collection).filter("source_id >= 0").build())
        flush()
        vectors.forEach(::upsert)
    }

    override fun close() {
        maintenanceClient.close()
        client.close()
    }

    private fun flush() {
        val response = maintenanceClient.flush(FlushParam.newBuilder()
            .withCollectionNames(listOf(collection)).withSyncFlush(true).build())
        if(response.status != R.Status.Success.code) unavailable("MILVUS_FLUSH_FAILED")
    }

    private fun row(sourceId: Long, vector: List<Float>) = JsonObject().apply {
        addProperty("source_id", sourceId)
        add("embedding", JsonArray().apply { vector.forEach(::add) })
    }

    private fun VectorSearchFilter.expression(): String {
        val clauses = mutableListOf<String>()
        if (sourceIds.isNotEmpty()) clauses += "source_id in [${sourceIds.sorted().joinToString(",")}]"
        excludeSourceId?.let { clauses += "source_id != $it" }
        return clauses.joinToString(" and ")
    }

    private fun invalid(reasonCode:String):Nothing = throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun unavailable(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}

@Component
@ConditionalOnProperty(prefix = "app.milvus", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DisabledMilvusGateway : VectorIndexGateway {
    override val available: Boolean = false
    override fun upsert(sourceId: Long, vector: List<Float>): String = unavailable()
    override fun search(vector: List<Float>, topK: Int, filter: VectorSearchFilter):List<VectorCandidate> = unavailable()
    override fun delete(sourceId: Long):Unit = unavailable()
    override fun rebuild(vectors: Map<Long, List<Float>>):Unit = unavailable()
    private fun unavailable():Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,"MILVUS_DISABLED")
}

@Component
class DerivedIndexSyncWorker(
    private val repository: DerivedIndexRepository,
    private val search: SearchIndexGateway,
    private val vectors: VectorIndexGateway,
    private val embeddings: EmbeddingProvider,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val outboundPrivacy:com.feedback.service.masking.OutboundPrivacyGate?=null
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun sync() { seedOutbox(); repeat(100) { claimOne()?.let(::process) ?: return } }

    /** 在任何外部调用之前，先把库里的持久状态转成稳定、唯一的发件箱任务。 */
    fun seedOutbox() {
        val ruleVersion=outboundPrivacy?.activeVersion()?:"LEGACY"
        val vectorMode=if(vectors.available)"VECTOR_ON" else "VECTOR_OFF"
        repository.seed(ruleVersion, vectorMode)
    }

    /**
     * 认领一条待同步的索引任务（含 fencing 令牌）。
     *
     * 公开而不是私有：MQ 轨（`IndexMqTrack`）的两半就是「认领」与
     * 「处理 [process] 认领到的那一条」，它必须能把这两步分别放在
     * 中继与消费者两侧执行。轮询轨（[sync]）在同一台机器的同一个循环里调它们。
     *
     * 持有者标识在这里现取一个随机值，**不由调用方传入**：fencing 令牌的意义是
     * 「这一次领取是唯一的」，如果调用方能决定持有者，那么两次领取可以用同一个标识，
     * 令牌就退化成了摆设。
     */
    fun claimOne(): ClaimedIndexTask? {
        val holder = "index-${java.util.UUID.randomUUID()}"
        return repository.claim(holder)
    }

    /**
     * 处理一条已认领的任务，并把结果写回（含 fencing 校验）。
     *
     * 公开的理由见 [claimOne]。**传入的任务必须来自本进程的某一次 [claimOne]**：
     * 写回时带的 `leaseHolder` 与 `leaseGeneration` 是那次认领发出的令牌，
     * 拼错或复用别的任务上的令牌会被数据库拒绝成 [IndexTaskWriteOutcome.LEASE_LOST]——
     * 拒绝本身是安全的，但那条任务的状态不会被推进，只能等租约过期后重来。
     */
    fun process(task: ClaimedIndexTask) {
        val id=task.id; val retry=task.retryCount
        val node=mapper.readTree(task.inputSnapshot); val type=node["entityType"].asText(); val entityId=node["entityId"].asLong()
        val operation=node["operation"].asText()
        // 写回一律带上本次 claim 的 holder 与 generation：只有它们同时匹配，才说明这次结果仍然属于当前租约。
        val outcome = runCatching { if(operation=="DELETE") delete(type,entityId) else upsert(type,entityId) }
            .fold(
                onSuccess = { repository.complete(id, task.leaseHolder, task.leaseGeneration) },
                onFailure = { error ->
                    // 对当前内容/规则版本而言，隐私拒绝是确定性的。重试它
                    // 只会饿死后面安全的索引任务；内容或规则变更会
                    // 生成新的稳定发件箱键，那才是恢复路径。
                    val attempts=retry+1
                    val terminal=error is com.feedback.service.masking.OutboundPrivacyException || attempts>=5
                    val delay=(1L shl retry.coerceAtMost(6))*30
                    repository.fail(id, task.leaseHolder, task.leaseGeneration, attempts, terminal, delay,
                        com.feedback.common.SafeErrorDetail.summary(error,"INDEX_SYNC"))
                }
            )
        // 被 fencing 拒绝说明租约已经被新 Worker 接管：旧结果必须丢弃，既不能推进状态，也不能当作
        // 本任务失败去重试（重试只会和新 Worker 抢同一个任务）。这是 fencing 生效的正常路径，不是故障。
        if (outcome == IndexTaskWriteOutcome.LEASE_LOST) {
            log.debug("index outbox write-back fenced task_id={} holder={} generation={}",
                id, task.leaseHolder, task.leaseGeneration)
            return
        }
    }

    private fun upsert(type:String,id:Long) {
        if(type=="FEEDBACK") {
            val r=repository.feedback(id) ?: return delete(type,id)
            outboundPrivacy?.requireSafe("SEARCH_VECTOR_INDEX_CONTENT",id,r.title,r.description)
            // AI 产出有自己的生命周期。不安全的过期摘要要拒绝进入
            // 派生索引，但绝不能因此把原本安全的源文档降级。
            outboundPrivacy?.requireSafe("SEARCH_VECTOR_INDEX_AI",null,r.summary)
            val text="${r.title?:""} ${r.description?:""}"
            val searchId=search.upsert(SearchDocument(id,r.channel,r.title,r.description,r.functionModule,r.category,r.severity,r.summary))
            repository.markFeedbackSearch(id,searchId)
            if(vectors.available){
                val vectorId=vectors.upsert(id,embeddings.embed(text))
                repository.markFeedbackVector(id,vectorId,embeddings.modelVersion,
                    outboundPrivacy?.activeVersion()?:"LEGACY",embeddings.dimension)
            }else repository.markFeedbackVectorUnavailable(id,outboundPrivacy?.activeVersion()?:"LEGACY")
        } else {
            val r=repository.cluster(id)?:return
            if(r.clusterStatus in setOf("MERGED","SPLIT_ARCHIVED","ARCHIVED")) return delete(type,id)
            val externalId=-id
            outboundPrivacy?.requireSafe("CLUSTER_INDEX",null,r.clusterName,r.canonicalSymptom)
            val text="${r.clusterName} ${r.canonicalSymptom?:""}"
            search.upsert(SearchDocument(externalId,"CLUSTER",r.clusterName,r.canonicalSymptom,r.functionModule,r.issueCategory,null,null))
            repository.markClusterSearch(id)
            if(vectors.available){
                vectors.upsert(externalId,embeddings.embed(text))
                repository.markClusterVector(id,outboundPrivacy?.activeVersion()?:"LEGACY")
            } else repository.markClusterVectorUnavailable(id,outboundPrivacy?.activeVersion()?:"LEGACY")
        }
    }
    private fun delete(type:String,id:Long) {
        val externalId=if(type=="CLUSTER")-id else id; search.delete(externalId)
        if(vectors.available)vectors.delete(externalId)
        val vectorStatus=if(vectors.available)"SYNCED" else "FAILED"
        if(type=="CLUSTER") repository.markClusterDeleted(id,vectorStatus)
        else repository.markFeedbackDeleted(id,vectorStatus)
    }

    fun rebuild(sourceId: Long) { repository.markFeedbackPending(sourceId); seedOutbox() }
}


/**
 * 派生索引同步的调度入口。
 *
 * ## MQ 模式下**整轨空转**
 *
 * `app.mq.enabled=true` 时本方法立刻返回：认领会写 `task_status='PROCESSING'` 并推进
 * `lease_generation`，两条轨同时认领会导致后认领的那次把先认领的那次 fencing 掉，
 * 于是**已完成的索引结果被丢弃、任务回到待同步**——重复做一遍还只是浪费，
 * 而 fencing 生效的方向是「后来的赢」，先做的白做。所以两条轨必须互斥，不能并行。
 *
 * 空转不等于停跑：同步由 `IndexMqTrack` 驱动（中继发号、消费者按任务处理）。
 * 当前走哪条轨由启动时的 `MqTrackReporter` 打印。
 *
 * 与 `NotificationWorkerScheduler` 的开关位置一致，理由见那个类的说明。
 */
@Component
class DerivedIndexSyncScheduler(
    private val worker: DerivedIndexSyncWorker,
    private val properties: MqProperties
) {
    fun sync() {
        if (properties.enabled) return
        worker.sync()
    }
}
