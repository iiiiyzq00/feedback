package com.feedback.persistence

import org.apache.ibatis.builder.xml.XMLMapperBuilder
import org.apache.ibatis.session.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.apache.ibatis.io.Resources

class AuthDataPermissionMyBatisContractTest {
    @Test fun `permission mapper is a bounded single table query`() {
        val resource="mybatis/mapper/auth/AuthDataPermissionMapper.xml"
        Resources.getResourceAsReader(resource).use { reader ->
            XMLMapperBuilder(reader,Configuration(),resource,mutableMapOf()).parse()
        }
        val xml=Resources.getResourceAsReader(resource).use { it.readText() }
        assertTrue(xml.contains("FROM t_auth_data_permission"))
        assertTrue(xml.contains("LIMIT #{limit}"))
        assertTrue(xml.contains("<foreach"))
        assertFalse(Regex("\\bJOIN\\b",RegexOption.IGNORE_CASE).containsMatchIn(xml))
        assertFalse(Regex("FROM\\s+[^<]+,",RegexOption.IGNORE_CASE).containsMatchIn(xml))
    }
}
