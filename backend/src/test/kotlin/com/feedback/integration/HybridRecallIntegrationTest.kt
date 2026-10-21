package com.feedback.integration

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest_client.RestClientTransport
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.service.sync.*
import org.apache.http.HttpHost
import org.elasticsearch.client.Request
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.support.DefaultListableBeanFactory

@EnabledIfSystemProperty(named = "day4.hybrid.enabled", matches = "true")
class HybridRecallIntegrationTest {
    @Test
    @Timeout(180)
    fun `real elasticsearch and milvus fuse bge semantic candidates`() {
        val modelPath = System.getProperty("day4.bge.path") ?: error("day4.bge.path is required")
        val index = "day4_hybrid_verify"
        RestClient.builder(HttpHost("127.0.0.1", 9200, "http")).build().use { restClient ->
            runCatching { restClient.performRequest(Request("DELETE", "/$index")) }
            restClient.performRequest(Request("PUT", "/$index").apply { setJsonEntity(INDEX_MAPPING) })
            RestClientTransport(restClient, JacksonJsonpMapper(ObjectMapper().findAndRegisterModules())).use { transport ->
                val esClient = ElasticsearchClient(transport)
                val beans = DefaultListableBeanFactory().apply { registerSingleton("elasticsearchClient", esClient) }
                val searchGateway = ElasticsearchGateway(beans.getBeanProvider(ElasticsearchClient::class.java), index)
                searchGateway.upsert(SearchDocument(101, "SDK", "AI辅导启动后闪退", "打开AI助教应用立即崩溃", "AI_TUTOR", "BUG", "HIGH", "AI辅导闪退"))
                searchGateway.upsert(SearchDocument(202, "JIRA", "视频播放卡顿", "课程视频画面不流畅", "VIDEO", "PERFORMANCE", "MEDIUM", "视频卡顿"))
                restClient.performRequest(Request("POST", "/$index/_refresh"))

                BgeM3OnnxEmbeddingProvider(modelPath, "onnx/model.onnx", 128).use { embedding ->
                    MilvusGateway("127.0.0.1", 19530, "hybrid_recall_1024_verify", 1024).use { vectorGateway ->
                        vectorGateway.rebuild(mapOf(
                            101L to embedding.embed("AI辅导启动后闪退，打开AI助教应用立即崩溃"),
                            202L to embedding.embed("视频播放卡顿，课程视频画面不流畅")
                        ))
                        val result = HybridCandidateRetriever(searchGateway, vectorGateway, embedding)
                            .retrieve("AI辅导启动闪退", 2)
                        assertEquals(101L, result.first().sourceId)
                        assertTrue(result.first().textRank != null)
                        assertTrue(result.first().vectorRank != null)
                    }
                }
            }
        }
    }

    companion object {
        private val INDEX_MAPPING = """
            {
              "mappings": {
                "properties": {
                  "title": {"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "description": {"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "summary": {"type":"text","analyzer":"ik_max_word","search_analyzer":"ik_smart"},
                  "functionModule": {"type":"keyword"},
                  "category": {"type":"keyword"}
                }
              }
            }
        """.trimIndent()
    }
}
