package com.feedback.persistence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class CoreFeedbackMyBatisContractTest {
    private val root = Path.of("src/main")

    @Test
    fun `source and content persistence is single-table mybatis without embedded business sql`() {
        val kotlinFiles = listOf(
            root.resolve("kotlin/com/feedback/repository/FeedbackSourceRepository.kt"),
            root.resolve("kotlin/com/feedback/repository/FeedbackContentRepository.kt"),
            root.resolve("kotlin/com/feedback/mapper/core/CoreFeedbackSourceMapper.kt"),
            root.resolve("kotlin/com/feedback/mapper/core/CoreFeedbackContentMapper.kt"),
            root.resolve("kotlin/com/feedback/model/feedback/FeedbackSource.kt"),
            root.resolve("kotlin/com/feedback/model/feedback/FeedbackContent.kt")
        )
        kotlinFiles.forEach { file ->
            val source = Files.readString(file)
            listOf("JdbcTemplate", "EntityManager", "JpaRepository", "@Entity", "jakarta.persistence").forEach {
                forbidden -> assertFalse(source.contains(forbidden), "$file contains $forbidden")
            }
            assertFalse(Regex("(?i)\\b(select|insert|update|delete)\\s+(into\\s+|from\\s+)?t_feedback_").containsMatchIn(source), file.toString())
        }

        listOf("CoreFeedbackSourceMapper.xml", "CoreFeedbackContentMapper.xml").forEach { name ->
            val file = root.resolve("resources/mybatis/mapper/core/$name")
            DocumentBuilderFactory.newInstance().apply {
                // mapper XML 的 DOCTYPE 指向 https://mybatis.org/dtd/mybatis-3-mapper.dtd，
                // 默认配置下 DocumentBuilder 会**真的去下载它**，于是这条断言的结果取决于出网：
                // 本机就出现过连接超时（ConnectException，栈里是 startDTDEntity）导致它变红，
                // 而它要验的「单表、无内嵌 SQL」跟网络毫无关系。
                // 生产路径不受影响 —— MyBatis 的 XMLMapperBuilder 自带 XMLMapperEntityResolver，
                // 从 classpath 供 DTD；只有测试里这个裸的 DocumentBuilderFactory 会出网。
                // 顺带这也是标准的 XXE 加固：不让解析器去取外部实体。
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }.newDocumentBuilder().parse(file.toFile())
            val xml = Files.readString(file).lowercase()
            assertFalse(Regex("\\bjoin\\b|\\bexists\\s*\\(").containsMatchIn(xml), "$name crosses a table boundary")
            val tables = Regex("(?i)(?:from|into|update)\\s+(t_[a-z0-9_]+)")
                .findAll(xml).map { it.groupValues[1] }.toSet()
            assertTrue(tables.size == 1, "$name references $tables")
        }
    }
}
