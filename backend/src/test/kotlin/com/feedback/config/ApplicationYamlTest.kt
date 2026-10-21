package com.feedback.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

class ApplicationYamlTest {
    @Test
    fun `application yaml has no duplicate mapping keys`() {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val input = requireNotNull(javaClass.getResourceAsStream("/application.yml"))
        input.use { assertNotNull(Yaml(options).load<Any>(it)) }
    }
}
