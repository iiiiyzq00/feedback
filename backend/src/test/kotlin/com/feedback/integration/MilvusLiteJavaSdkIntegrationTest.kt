package com.feedback.integration

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.milvus.v2.client.ConnectConfig
import io.milvus.v2.client.MilvusClientV2
import io.milvus.v2.common.DataType
import io.milvus.v2.service.collection.request.CreateCollectionReq
import io.milvus.v2.service.collection.request.HasCollectionReq
import io.milvus.v2.service.vector.request.InsertReq
import io.milvus.v2.service.vector.request.UpsertReq
import io.milvus.v2.service.vector.request.SearchReq
import io.milvus.v2.service.vector.request.data.FloatVec
import io.milvus.client.MilvusServiceClient
import io.milvus.param.ConnectParam
import io.milvus.param.collection.FlushParam
import io.milvus.param.MetricType
import io.milvus.param.dml.SearchParam
import io.milvus.response.SearchResultsWrapper
import com.feedback.service.sync.MilvusGateway
import com.feedback.service.sync.VectorSearchFilter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "day4.milvus.lite.enabled", matches = "true")
class MilvusLiteJavaSdkIntegrationTest {
    @Test
    @Timeout(30)
    fun `production gateway supports upsert filtered search delete and rebuild`() {
        MilvusGateway("127.0.0.1", 19530, "kotlin_gateway_verify", 4).use { gateway ->
            gateway.rebuild(emptyMap())
            gateway.upsert(11, listOf(1f, 0f, 0f, 0f))
            gateway.upsert(22, listOf(0.8f, 0.2f, 0f, 0f))
            gateway.upsert(33, listOf(0f, 1f, 0f, 0f))

            val filtered = gateway.search(
                listOf(1f, 0f, 0f, 0f), 2,
                VectorSearchFilter(sourceIds = setOf(11, 22, 33), excludeSourceId = 11)
            )
            assertEquals(listOf(22L, 33L), filtered.map { it.sourceId })

            gateway.delete(22)
            assertFalse(gateway.search(listOf(1f, 0f, 0f, 0f), 3).any { it.sourceId == 22L })

            gateway.rebuild(mapOf(44L to listOf(0f, 0f, 1f, 0f)))
            assertEquals(listOf(44L), gateway.search(listOf(0f, 0f, 1f, 0f), 3).map { it.sourceId })
        }
    }

    @Test
    @Timeout(30)
    fun `java sdk connects to lite and preserves top k data across restart`() {
        println("MILESTONE connect-start")
        val client = MilvusClientV2(ConnectConfig.builder().uri("http://127.0.0.1:19530").build())
        try {
            println("MILESTONE connect-created")
            assertTrue(client.clientIsReady())
            println("MILESTONE client-ready")
            val exists = client.hasCollection(HasCollectionReq.builder().collectionName(COLLECTION).build())
            println("MILESTONE has-collection=$exists")
            if (!exists) {
                client.createCollection(CreateCollectionReq.builder()
                    .collectionName(COLLECTION)
                    .dimension(4)
                    .primaryFieldName("source_id")
                    .idType(DataType.Int64)
                    .vectorFieldName("embedding")
                    .metricType("COSINE")
                    .autoID(false)
                    .enableDynamicField(true)
                    .build())
                println("MILESTONE collection-created")
            }

            if (System.getProperty("day4.milvus.phase", "write") == "write") {
                val first = row(101, floatArrayOf(1f, 0f, 0f, 0f), "AI tutor crash")
                val second = row(202, floatArrayOf(0f, 1f, 0f, 0f), "video playback lag")
                client.upsert(UpsertReq.builder().collectionName(COLLECTION).data(listOf(first)).build())
                client.upsert(UpsertReq.builder().collectionName(COLLECTION).data(listOf(second)).build())
                val maintenance = MilvusServiceClient(ConnectParam.newBuilder().withHost("127.0.0.1").withPort(19530).build())
                try {
                    maintenance.flush(FlushParam.newBuilder().withCollectionNames(listOf(COLLECTION)).withSyncFlush(true).build())
                } finally {
                    maintenance.close()
                }
                println("MILESTONE inserted")
            }

            val searchClient = MilvusServiceClient(ConnectParam.newBuilder().withHost("127.0.0.1").withPort(19530).build())
            val response = try {
                searchClient.search(SearchParam.newBuilder().withCollectionName(COLLECTION)
                    .withVectorFieldName("embedding").withFloatVectors(listOf(listOf(0.99f, 0.01f, 0f, 0f)))
                    .withMetricType(MetricType.COSINE).withTopK(2).withOutFields(listOf("source_id", "summary")).build())
            } finally {
                searchClient.close()
            }
            println("MILESTONE searched")
            val results = SearchResultsWrapper(response.data.results).getIDScore(0)
            assertEquals(2, results.size)
            assertEquals(101L, results.first().longID)
            assertTrue(results.first().score > results.last().score)
        } finally {
            client.close()
        }
    }

    private fun row(sourceId: Long, vector: FloatArray, summary: String) = JsonObject().apply {
        addProperty("source_id", sourceId)
        add("embedding", JsonArray().apply { vector.forEach(::add) })
        addProperty("summary", summary)
    }

    companion object { private const val COLLECTION = "kotlin_lite_verify" }
}
