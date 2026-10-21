package com.feedback.service.alert

import com.feedback.model.po.alert.VersionContentPo
import com.feedback.model.po.alert.VersionReleasePo
import com.feedback.model.po.alert.VersionSourcePo
import com.feedback.repository.alert.VersionFeedbackFact
import com.feedback.repository.alert.VersionReleaseRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VersionReleaseFactServiceTest {
    private val repository=mockk<VersionReleaseRepository>()
    private val service=VersionReleaseFactService(repository)

    @Test fun `regular and blocking regression preserve thresholds and inclusive release boundary`() {
        val end=LocalDateTime.of(2026,9,2,12,0)
        val release=VersionReleasePo(1,"com.demo.app","2.0","1.9",end.minusMinutes(150),"ACTIVE",end.minusDays(1))
        every { repository.activeBefore(end) } returns listOf(release)
        val facts=buildList {
            repeat(20){index->
                add(VersionFeedbackFact(
                    VersionSourcePo(index+1L,"com.demo.app","2.0",if(index%2==0)"SDK" else "JIRA",end.minusMinutes(30)),
                    VersionContentPo(index+1L,if(index<6)"S1" else "S2"),setOf(100L),
                ))
            }
            repeat(20){index->
                val id=100+index.toLong()
                add(VersionFeedbackFact(
                    VersionSourcePo(id,"com.demo.app","1.9","SDK",release.releasedAt.minusHours(1)),
                    VersionContentPo(id,"S2"),if(index<5)setOf(100L) else emptySet(),
                ))
            }
        }
        every { repository.forEachFact(listOf(release),end,null,any()) } answers {
            val consumer=arg<(VersionFeedbackFact)->Unit>(3);facts.forEach(consumer)
        }
        assertEquals(1.0,service.regressionCandidates(end))
        assertEquals(1.0,service.blockingRegressionCandidates(end,120))
    }

    @Test fun `blocking regression rejects a cluster below severe ratio`() {
        val end=LocalDateTime.of(2026,9,2,12,0)
        val release=VersionReleasePo(1,"com.demo.app","2.0","1.9",end.minusHours(1),"ACTIVE",end.minusDays(1))
        every { repository.activeBefore(end) } returns listOf(release)
        val facts=(1L..20L).map{index->VersionFeedbackFact(
            VersionSourcePo(index,"com.demo.app","2.0",if(index%2L==0L)"SDK" else "JIRA",end.minusMinutes(10)),
            VersionContentPo(index,if(index<=5)"S1" else "S2"),setOf(100L),
        )}+(21L..40L).map{index->VersionFeedbackFact(
            VersionSourcePo(index,"com.demo.app","1.9","SDK",release.releasedAt.minusMinutes(10)),VersionContentPo(index,"S2"),emptySet(),
        )}
        every { repository.forEachFact(listOf(release),end,null,any()) } answers { val consumer=arg<(VersionFeedbackFact)->Unit>(3);facts.forEach(consumer) }
        assertEquals(0.0,service.blockingRegressionCandidates(end,120))
    }
}
