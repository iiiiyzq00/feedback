package com.feedback.service.sync

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DerivedIndexDegradedContractTest {
    @Test
    fun `disabled vector gateway is an explicit capability and does not block search commit`() {
        assertFalse(DisabledMilvusGateway().available)
        val source=Files.readString(Path.of("src/main/kotlin/com/feedback/service/sync/DerivedIndexSync.kt"))
        assertTrue(source.contains("if(vectors.available)"))
        assertTrue(source.contains("val vectorMode=if(vectors.available)\"VECTOR_ON\" else \"VECTOR_OFF\""))
        assertTrue(source.contains("repository.seed(ruleVersion, vectorMode)"))
        val feedbackMapper = Files.readString(Path.of("src/main/resources/mybatis/mapper/sync/IndexFeedbackAiMapper.xml"))
        val clusterMapper = Files.readString(Path.of("src/main/resources/mybatis/mapper/sync/IndexClusterMapper.xml"))
        listOf(feedbackMapper, clusterMapper).forEach { mapper ->
            assertTrue(mapper.contains("search_index_status='SYNCED'"))
            assertTrue(mapper.contains("vector_index_status='FAILED'"))
        }
    }
}
