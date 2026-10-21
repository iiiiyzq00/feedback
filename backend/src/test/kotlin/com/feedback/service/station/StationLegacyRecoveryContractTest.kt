package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class StationLegacyRecoveryContractTest {
    @Test
    fun `controlled legacy import never treats export absence as deletion authority`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/feedback/service/station/StationLegacyExportImportService.kt"))
        assertTrue(source.contains("importControlledExport"))
        assertTrue(source.contains("recoverOperationFailures"))
        assertFalse(source.contains("importAndReplaceMock"))
        assertFalse(source.contains("removeStationSources"))
        assertFalse(source.contains("removedMockRows"))
    }
}
