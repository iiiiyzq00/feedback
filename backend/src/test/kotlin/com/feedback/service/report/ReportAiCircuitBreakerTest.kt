package com.feedback.service.report

import com.feedback.model.po.report.ReportAiCircuitStatePo
import com.feedback.repository.report.ReportAiCircuitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportAiCircuitBreakerTest {
 @Test fun `open circuit blocks calls before probe time`(){val repository=mockk<ReportAiCircuitRepository>(relaxed=true);every{repository.lock()} returns ReportAiCircuitStatePo("OPEN",10);every{repository.probeDue()} returns false;assertFalse(ReportAiCircuitBreaker(repository).acquireCallPermit());verify(exactly=1){repository.ensure()};verify(exactly=0){repository.halfOpen()}}
 @Test fun `expired open circuit admits one half open probe`(){val repository=mockk<ReportAiCircuitRepository>(relaxed=true);every{repository.lock()} returns ReportAiCircuitStatePo("OPEN",10);every{repository.probeDue()} returns true;assertTrue(ReportAiCircuitBreaker(repository).acquireCallPermit());verify{repository.halfOpen()}}
 @Test fun `tenth failure opens circuit and records incident audit`(){val repository=mockk<ReportAiCircuitRepository>(relaxed=true);every{repository.lock()} returns ReportAiCircuitStatePo("CLOSED",9);ReportAiCircuitBreaker(repository).recordFailure(IllegalStateException("provider"));verify{repository.failure(10,any(),"OPEN",true)};verify{repository.audit("REPORT_AI_CIRCUIT_OPEN",match{"\"consecutiveFailures\":10" in it})}}
}
