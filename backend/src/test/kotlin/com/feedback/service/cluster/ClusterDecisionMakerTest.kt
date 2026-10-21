package com.feedback.service.cluster

import com.feedback.model.cluster.Cluster
import com.feedback.model.feedback.FeedbackAi
import com.feedback.model.feedback.FeedbackContent
import com.feedback.model.feedback.FeedbackSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ClusterDecisionMakerTest {
    private val maker = ClusterDecisionMaker()
    private val policy = ClusterDecisionPolicy(
        BigDecimal("0.900000"), BigDecimal("0.750000"), BigDecimal("0.030000"),
        "cluster-decision-v2", "cluster-config-v2"
    )

    @Test
    fun `same normalized error signature is auto high`() {
        val profile = profile(error = "NULLPOINTEREXCEPTION")
        val evidence = maker.evaluate(profile, cluster(error = "NULLPOINTEREXCEPTION"))
        val decision = maker.decide(profile, listOf(evidence), policy)
        assertEquals("AUTO_HIGH", decision.action)
        assertEquals(1L, decision.selected?.cluster?.id)
    }

    @Test
    fun `different application module is context and not a hard problem conflict`() {
        val profile = profile(module = "AI_TUTOR")
        val evidence = maker.evaluate(profile, cluster(module = "VIDEO"))
        assertFalse("FUNCTION_MODULE" in evidence.conflicts)
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `normalized exact text auto links without structural anchors`() {
        val profile = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
        val candidate = cluster(module = null, category = null, symptom = profile.symptom)
        val evidence = maker.evaluate(profile, candidate)
        assertEquals(BigDecimal("1.000000"), evidence.lexicalScore)
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `normalized prefixes synonyms and record numbers still auto link`() {
        val profile = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
            .copy(symptom = "用户反馈：AI助教点击后crash；记录编号 R-7。")
        val candidate = cluster(
            module = null,
            category = null,
            symptom = "【学习机】AI助教点击后闪退",
            target = null,
            trigger = null
        )
        val evidence = maker.evaluate(profile, candidate)

        assertEquals(BigDecimal("1.000000"), evidence.lexicalScore)
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `safe navigation and freeze synonyms still auto link`() {
        val navigation = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
            .copy(symptom = "页面跳不进去")
        val navigationEvidence = maker.evaluate(
            navigation,
            cluster(module = null, category = null, symptom = "页面无法进入", target = null, trigger = null)
        )
        val freeze = navigation.copy(symptom = "页面一直在这儿卡住")
        val freezeEvidence = maker.evaluate(
            freeze,
            cluster(module = null, category = null, symptom = "页面一直在这里卡死", target = null, trigger = null)
        )

        assertEquals("AUTO_HIGH", maker.decide(navigation, listOf(navigationEvidence), policy).action)
        assertEquals("AUTO_HIGH", maker.decide(freeze, listOf(freezeEvidence), policy).action)
    }

    @Test
    fun `cross channel substring with only issue category does not auto merge`() {
        val profile = profile(module = null, category = "BUG", target = null, trigger = null, severity = "S3")
            .copy(symptom = "这是我课上[NAME_REMOVED]的笔记。", sourceChannel = "STATION_FEEDBACK")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = "BUG", symptom = "[NAME_REMOVED]的笔记", target = null, trigger = null)
        ).copy(crossChannelEvidence = true)

        assertEquals(setOf("ISSUE_CATEGORY"), evidence.coreMatches)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `cross channel exact concrete phenomenon can auto merge`() {
        val profile = profile(module = null, category = "BUG", target = null, trigger = null, severity = "S3")
            .copy(symptom = "无法充电", sourceChannel = "STATION_FEEDBACK")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = "BUG", symptom = "无法充电", target = null, trigger = null)
        ).copy(crossChannelEvidence = true)

        assertEquals(BigDecimal("1.000000"), evidence.lexicalScore)
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `cross channel generic placeholder never auto merges`() {
        val profile = profile(module = null, category = "BUG", target = null, trigger = null, severity = "S3")
            .copy(symptom = "问题现象待归纳/其他/测试", sourceChannel = "STATION_FEEDBACK")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = "BUG", symptom = "问题现象待归纳/其他/测试", target = null, trigger = null)
        ).copy(crossChannelEvidence = true)

        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same channel generic placeholder never auto merges without a substantive anchor`() {
        val profile = profile(module = null, category = "BUG", target = null, trigger = null, severity = "S3")
            .copy(symptom = "其他/恶/占位置/一")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = "BUG", symptom = "其他/恶/占位置/一", target = null, trigger = null)
        )

        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `identical low information feedback uses one data quality cluster`() {
        val profile = profile(module = null, category = null, target = null, trigger = null, severity = null)
            .copy(symptom = ProductPhenomenonFormatter.LOW_INFORMATION, problemType = null)
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = null, category = null, symptom = ProductPhenomenonFormatter.LOW_INFORMATION,
                target = null, trigger = null
            )
        )

        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `low information feedback never joins a concrete problem`() {
        val profile = profile(module = null, category = null, target = null, trigger = null, severity = null)
            .copy(symptom = ProductPhenomenonFormatter.LOW_INFORMATION, problemType = null)
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = null, symptom = "使用过程中出现卡顿", target = null, trigger = null)
        )

        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `one structural anchor plus a close symptom variant auto links`() {
        val profile = profile(module = "COURSE", category = null, target = null, trigger = null, severity = "S3")
            .copy(symptom = "AI助教点击后闪退")
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = "COURSE",
                category = null,
                symptom = "打开AI助教，点击进入后应用闪退",
                target = null,
                trigger = null
            )
        )

        assertEquals(setOf("FUNCTION_MODULE"), evidence.coreMatches)
        assertTrue(evidence.lexicalScore >= BigDecimal("0.940000"))
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `channel is not a partition key for close cross channel phenomena`() {
        val profile = profile(module = "COURSE", category = null, target = null, trigger = null, severity = "S3")
            .copy(sourceChannel = "STATION_FEEDBACK", symptom = "AI助教点击后闪退")
        val evidence = maker.evaluate(
            profile,
            // 该候选代表 JIRA/QUALITY 侧的簇；归一化后的现象
            // 很接近，但刻意不做到逐字节相同。
            cluster(
                module = "COURSE", category = null, target = null, trigger = null,
                symptom = "打开AI助教，点击进入后应用闪退"
            )
        ).copy(crossChannelEvidence = true)

        assertNotEquals(profile.symptom, evidence.cluster.canonicalSymptom)
        assertEquals(setOf("FUNCTION_MODULE"), evidence.coreMatches)
        assertTrue(evidence.lexicalScore >= BigDecimal("0.940000"))
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `different playback and download symptoms stay in separate clusters`() {
        val profile = profile(module = "COURSE", category = "BUG", target = "课程", trigger = null, severity = "S3")
            .copy(symptom = "课程播放失败")
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = "COURSE",
                category = "BUG",
                symptom = "课程下载失败",
                target = "课程",
                trigger = null
            )
        )

        assertTrue(evidence.lexicalScore < BigDecimal("0.940000"))
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same vague symptom on different target objects stays separate`() {
        val profile = profile(module = null, category = null, target = "麦克风", trigger = "打开麦克风", severity = "S3")
            .copy(symptom = "打开后没有反应")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = null, symptom = "打开后没有反应", target = "摄像头", trigger = "打开摄像头")
        )

        assertTrue("TARGET_OBJECT" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same vague failure on upload and download stays separate`() {
        val profile = profile(module = null, category = null, target = "课程", trigger = "上传课程", severity = "S3")
            .copy(symptom = "操作失败")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = null, symptom = "操作失败", target = "课程", trigger = "下载课程")
        )

        assertTrue("TRIGGER" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `recognition grading and content errors are different symptom classes`() {
        val recognition = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
            .copy(symptom = "把手写符号识别成其他符号")
        val grading = maker.evaluate(
            recognition,
            cluster(module = null, category = null, symptom = "答案写对了却被判错", target = null, trigger = null)
        )
        val content = maker.evaluate(
            recognition,
            cluster(module = null, category = null, symptom = "题目内容有错误", target = null, trigger = null)
        )

        assertTrue("SYMPTOM" in grading.conflicts)
        assertTrue("SYMPTOM" in content.conflicts)
    }

    @Test
    fun `exact text below normalized length floor stays new`() {
        val profile = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
            .copy(symptom = "失败")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = null, symptom = "失败", target = null, trigger = null)
        )

        assertEquals(BigDecimal("1.000000"), evidence.lexicalScore)
        assertEquals(2, evidence.normalizedTextLength)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `single structural anchor with weak lexical evidence conservatively creates a new cluster`() {
        val profile = profile(module = null, category = "CONSULT", target = null, trigger = null, severity = "S3")
        val evidence = maker.evaluate(profile, cluster(module = null, category = "CONSULT", symptom = "课程下载失败", target = null, trigger = null))
        val decision = maker.decide(profile, listOf(evidence), policy)
        assertEquals("NEW_CLUSTER", decision.action)
    }

    @Test
    fun `single structural anchor plus strict vector evidence conservatively creates a new cluster`() {
        val profile = profile(module = null, category = "CONSULT", target = null, trigger = null, severity = "S3")
        val evidence = maker.evaluate(
            profile,
            cluster(module = null, category = "CONSULT", symptom = "完全不同的文字", target = null, trigger = null)
        ).copy(vectorScore = 0.99f)

        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same canonical symptom with different error signatures is a hard conflict`() {
        val profile = profile(error = "HTTP-500", severity = "S3")
        val evidence = maker.evaluate(profile, cluster(error = "HTTP-404"))

        assertTrue("ERROR_SIGNATURE" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `module and category with high vector evidence auto links high`() {
        val profile = profile(severity = "S3")
        val evidence = maker.evaluate(profile, cluster()).copy(vectorScore = 0.93f)
        val decision = maker.decide(profile, listOf(evidence.copy(lexicalScore = BigDecimal("0.500000"))), policy)
        assertEquals("AUTO_HIGH", decision.action)
    }

    @Test
    fun `high risk strong but non exact evidence goes to review`() {
        val profile = profile(severity = "S1", error = null)
            .copy(symptom = "AI助教点击后闪退")
        val evidence = maker.evaluate(
            profile,
            cluster(error = null, symptom = "打开AI助教，点击进入后应用闪退")
        )
        val decision = maker.decide(profile, listOf(evidence), policy)
        assertEquals("CANDIDATE", decision.action)
    }

    @Test
    fun `high risk exact text still auto links`() {
        val profile = profile(severity = "S1", error = null)
        val evidence = maker.evaluate(profile, cluster(error = null, symptom = profile.symptom))

        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `duplicate exact active clusters reuse one candidate instead of creating a third`() {
        val profile = profile()
        val first = maker.evaluate(profile, cluster(id = 1, symptom = "AI助教点击后闪退"))
        val second = maker.evaluate(profile, cluster(id = 2, symptom = "AI助教点击后闪退"))
        val decision = maker.decide(profile, listOf(first, second), policy)
        assertEquals("AUTO_HIGH", decision.action)
        assertEquals(1L, decision.selected?.cluster?.id)
    }

    @Test
    fun `weak close candidates do not create manual review noise`() {
        val profile = profile(severity = "S3")
        val first = maker.evaluate(profile, cluster(id = 1, symptom = "课程封面加载很慢", module = "AI_TUTOR", category = "BUG"))
        val second = maker.evaluate(profile, cluster(id = 2, symptom = "课程封面加载失败", module = "AI_TUTOR", category = "BUG"))
        assertTrue(first.lexicalScore < policy.reviewThreshold)
        assertTrue(second.lexicalScore < policy.reviewThreshold)
        val decision = maker.decide(profile, listOf(first, second), policy)
        assertEquals("NEW_CLUSTER", decision.action)
    }

    @Test
    fun `exact signature auto links when there is no competing boundary`() {
        val profile = profile(error = "HTTP-500")
        val first = maker.evaluate(profile, cluster(id = 1, error = "HTTP-500", symptom = "AI助教点击后闪退"))
        val second = maker.evaluate(profile, cluster(id = 2, symptom = "课程封面加载失败"))
        val decision = maker.decide(profile, listOf(first, second), policy)
        assertEquals("AUTO_HIGH", decision.action)
        assertEquals(1L, decision.selected?.cluster?.id)
    }

    @Test
    fun `resolved strong match becomes recurrence candidate`() {
        val profile = profile(error = "NULLPOINTEREXCEPTION")
        val evidence = maker.evaluate(profile, cluster(error = "NULLPOINTEREXCEPTION", status = "RESOLVED"))
        val decision = maker.decide(profile, listOf(evidence), policy)
        assertEquals("CANDIDATE", decision.action)
        assertTrue(evidence.historicalRecurrence)
    }

    @Test
    fun `contact and region identifiers never enter cluster profile`() {
        val extracted = ProblemProfileExtractor().extract(
            FeedbackSource(id = 7, sourceChannel = "SDK"),
            FeedbackContent(description = "联系[PHONE] AI助教闪退", functionModule = "AI_TUTOR", maskingStatus = "PASS"),
            FeedbackAi(aiSummary = "AI助教闪退", aiCategory = "BUG"),
            "LEARNING_MACHINE",
            mapOf("platform" to "course-app", "deviceModel" to "XT11A")
        )
        assertFalse(extracted.toString().contains("PHONE"))
        assertFalse(extracted.toString().contains("province", true))
    }

    @Test
    fun `specific source title is preferred over channel dependent ai summary`() {
        val extracted = ProblemProfileExtractor().extract(
            FeedbackSource(id = 8, sourceChannel = "REDBLACK"),
            FeedbackContent(title = "绘画画布撤销异常", description = "连续撤销后显示错误", functionModule = "DRAWING_CANVAS", maskingStatus = "PASS"),
            FeedbackAi(aiSummary = "撤销功能表现异常", aiCategory = "BUG"),
            "LEARNING_MACHINE"
        )

        assertEquals("绘画画布撤销异常", extracted.symptom)
        assertEquals("NORMALIZED_TITLE", extracted.fieldSources["symptom"])
    }

    @Test
    fun `AI classification does not become a hard cluster boundary`() {
        val extracted = ProblemProfileExtractor().extract(
            FeedbackSource(id = 9, sourceChannel = "STATION_FEEDBACK"),
            FeedbackContent(title = "其他错误", description = "打开课程后视频持续黑屏", maskingStatus = "PASS"),
            FeedbackAi(aiSummary = "其他错误", aiCategory = "CONSULT", aiSeverity = "S1"),
            "LEARNING_MACHINE"
        )

        assertEquals("播放视频时无法正常播放", extracted.symptom)
        assertEquals("MASKED_TEXT_PRIMARY", extracted.fieldSources["symptom"])
        assertNull(extracted.issueCategory)
        assertNull(extracted.severityLevel)
        assertEquals("MISSING", extracted.fieldSources["issueCategory"])
    }

    @Test
    fun `error signature removes stack line and dynamic inner class`() {
        val signature = ErrorSignatureNormalizer().normalize(
            "java.lang.NullPointerException at com.company.AiEngine\$Worker.run(AiEngine.kt:234)"
        )
        assertEquals("NULLPOINTEREXCEPTION|com.company.aiengine.run", signature)
    }

    @Test
    fun `TC classification codes are not error signatures`() {
        val normalizer = ErrorSignatureNormalizer()

        assertNull(normalizer.normalize("错误码 TC01"))
        assertNull(normalizer.normalize("错误码 TC-01"))
        assertNull(normalizer.normalize("错误码 TC_01"))
        assertNull(normalizer.normalize("错误码 TC:01"))
    }

    @Test
    fun `model and source identifiers are not error signatures without error context`() {
        val normalizer = ErrorSignatureNormalizer()

        assertNull(normalizer.normalize("设备型号 XTW31"))
        assertNull(normalizer.normalize("设备型号 XTA21"))
        assertNull(normalizer.normalize("Jira issue SPFB-1814"))
        assertNull(normalizer.normalize("下载渠道 APPSTORE-849"))
        assertNull(normalizer.normalize("市场版本 TOP16"))
    }

    @Test
    fun `TC classification code with weak text does not auto merge`() {
        val normalizer = ErrorSignatureNormalizer()
        val profile = profile(
            module = null, category = null, error = normalizer.normalize("TC01"),
            target = null, trigger = null, severity = "S3"
        ).copy(symptom = "一个完全不同的现象")
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = null, category = null, error = normalizer.normalize("TC01"),
                symptom = "另一个完全不同的问题", target = null, trigger = null
            )
        )

        assertNull(profile.errorSignature)
        assertNull(evidence.cluster.errorSignature)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `specific NullPointerException with sufficient structure still auto merges`() {
        val profile = profile(
            module = "AI_TUTOR", category = "BUG", error = "NULLPOINTEREXCEPTION",
            target = "助教", trigger = "点击AI助教", severity = "S3"
        )
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = "AI_TUTOR", category = "BUG", error = "NULLPOINTEREXCEPTION",
                symptom = "点击AI助教后应用崩溃", target = "助教", trigger = "点击AI助教"
            )
        )

        assertTrue(evidence.coreMatches.containsAll(setOf("FUNCTION_MODULE", "ISSUE_CATEGORY", "TARGET_OBJECT", "TRIGGER")))
        assertEquals("AUTO_HIGH", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `ranking context cannot promote weak semantic evidence into an automatic link`() {
        val profile = profile(module = "COURSE", category = "BUG", target = "课程", trigger = "播放课程", severity = "S3")
            .copy(symptom = "课程播放失败")
        val evidence = maker.evaluate(
            profile,
            cluster(module = "COURSE", category = "BUG", symptom = "课程下载失败", target = "课程", trigger = "下载课程")
        ).copy(rankingScore = BigDecimal("0.990000"))

        assertTrue(evidence.semanticScore < BigDecimal("0.940000"))
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `generic identical text without a stable anchor stays separate`() {
        listOf("页面异常", "操作失败", "功能不能用", "打开后没反应").forEach { symptom ->
            val vague = profile(module = null, category = null, target = null, trigger = null, severity = "S3")
                .copy(symptom = symptom)
            val evidence = maker.evaluate(
                vague, cluster(module = null, category = null, symptom = symptom, target = null, trigger = null)
            )
            assertEquals("NEW_CLUSTER", maker.decide(vague, listOf(evidence), policy).action, symptom)
        }
    }

    @Test
    fun `course container and video capability are distinct objects`() {
        val profile = profile(module = null, category = "BUG", target = "课程", trigger = null, severity = "S3")
            .copy(symptom = "课程打开失败")
        val evidence = maker.evaluate(
            profile, cluster(module = null, category = "BUG", symptom = "视频打开失败", target = "视频", trigger = null)
        )
        assertTrue("TARGET_OBJECT" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `opening and playing a video are distinct operations`() {
        val profile = profile(module = "VIDEO", category = "BUG", target = "视频", trigger = "打开视频", severity = "S3")
            .copy(symptom = "打开视频失败")
        val evidence = maker.evaluate(
            profile, cluster(module = "VIDEO", category = "BUG", symptom = "播放视频失败", target = "视频", trigger = "播放视频")
        )
        assertTrue("TRIGGER" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same weak stack frame cannot merge different problems`() {
        val frame = "com.example.common.dispatch"
        val profile = profile(module = null, category = "BUG", error = frame, target = null, trigger = null, severity = "S3")
            .copy(symptom = "网络连接反复断开", errorSignatureType = "STACK_FRAME")
        val evidence = maker.evaluate(
            profile, cluster(module = null, category = "BUG", error = frame, symptom = "设备无法充电", target = null, trigger = null)
        )
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `same exception plus only module and category cannot merge different failures`() {
        val profile = profile(module = "COURSE", category = "BUG", error = "NULLPOINTEREXCEPTION", target = null, trigger = null, severity = "S3")
            .copy(symptom = "课程播放黑屏", errorSignatureType = "EXCEPTION_TYPE")
        val evidence = maker.evaluate(
            profile, cluster(module = "COURSE", category = "BUG", error = "NULLPOINTEREXCEPTION",
                symptom = "课程下载后文件损坏", target = null, trigger = null)
        )
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `known and unknown failure modes conflict despite same exception object and operation`() {
        val profile = profile(module = "VIDEO", category = "BUG", error = "NULLPOINTEREXCEPTION",
            target = "视频", trigger = "播放视频", severity = "S3")
            .copy(symptom = "播放视频时加载失败", errorSignatureType = "EXCEPTION_TYPE")
        val evidence = maker.evaluate(
            profile, cluster(module = "VIDEO", category = "BUG", error = "NULLPOINTEREXCEPTION",
                symptom = "播放视频时黑屏", target = "视频", trigger = "播放视频")
        )
        assertTrue("SYMPTOM" in evidence.conflicts)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `each feedback selects one primary problem even when secondary phenomena are present`() {
        val extractor = ProblemProfileExtractor()
        val cases = listOf(
            "视频卡住后闪退，重新进入又没有声音" to ("播放视频时出现卡顿" to "FREEZE"),
            "手写符号识别错误，而且正确答案又被判错" to ("手写字符识别错误" to "RECOGNITION_ERROR"),
            "课程无法进入，同时网络连接异常" to ("无法进入课程" to "NAVIGATION"),
            "我明明做对了，却被系统改错题" to ("作答批改结果错误" to "GRADING_ERROR"),
            "怎么没有网络，还特别卡" to ("设备网络连接异常" to "NETWORK")
        )
        cases.forEachIndexed { index, (text, expected) ->
            val extracted = extractor.extract(
                FeedbackSource(id = 800L + index, sourceChannel = "SDK"),
                FeedbackContent(description = text, maskingStatus = "PASS"),
                FeedbackAi(), "LEARNING_MACHINE"
            )
            assertEquals("ACTIONABLE", extracted.informationQuality, text)
            assertEquals(expected.first, extracted.symptom, text)
            assertEquals(setOf(expected.second), extracted.failureModes, text)
            assertFalse(extracted.targetObject.orEmpty().startsWith("MULTI_ISSUE:"), text)
        }
        val explicitTitle = extractor.extract(
            FeedbackSource(id = 899, sourceChannel = "SDK"),
            FeedbackContent(title = "视频闪退", description = "播放时先卡住，重新进入后又没有声音", maskingStatus = "PASS"),
            FeedbackAi(), "LEARNING_MACHINE"
        )
        assertEquals("ACTIONABLE", explicitTitle.informationQuality)
        assertEquals("播放视频时出现卡顿", explicitTitle.symptom)
        assertEquals(setOf("FREEZE"), explicitTitle.failureModes)
        val causal = extractor.extract(
            FeedbackSource(id = 900, sourceChannel = "SDK"),
            FeedbackContent(description = "提交作业后页面一直转圈，导致无法完成提交", maskingStatus = "PASS"),
            FeedbackAi(), "LEARNING_MACHINE"
        )
        assertEquals("ACTIONABLE", causal.informationQuality)
        assertEquals(1, causal.failureModes.size)
    }

    @Test
    fun `submission spinner and inability to complete are one causal problem`() {
        val profile = profile(module = "HOMEWORK", category = "BUG", target = null, trigger = "点击提交作业", severity = "S3")
            .copy(symptom = "点击提交后无法完成提交")
        val evidence = maker.evaluate(
            profile,
            cluster(module = "HOMEWORK", category = "BUG", symptom = "提交作业后页面一直转圈",
                target = null, trigger = "提交作业")
        )
        assertFalse("SYMPTOM" in evidence.conflicts)
        val decision = maker.decide(profile, listOf(evidence), policy)
        assertEquals("AUTO_HIGH", decision.action, decision.reason)
    }

    @Test
    fun `cluster name coverage gate rejects a structurally similar member it cannot describe`() {
        val profile = profile(module = "VIDEO", category = "BUG", error = "NULLPOINTEREXCEPTION",
            target = "视频", trigger = "播放视频", severity = "S3").copy(symptom = "播放视频时无法播放")
        val evidence = maker.evaluate(
            profile, cluster(module = "VIDEO", category = "BUG", error = "NULLPOINTEREXCEPTION",
                symptom = "播放视频时黑屏", target = "视频", trigger = "播放视频")
        )
        val result = enforceClusterNameCoverage(
            profile, ClusterDecisionPlan("AUTO_HIGH", evidence, listOf(evidence), "结构相似")
        )
        assertEquals("NEW_CLUSTER", result.action)
        assertNull(result.selected)
    }

    @Test
    fun `cluster name coverage gate accepts a safely evolvable causal member`() {
        val profile = profile(module = "HOMEWORK", category = "BUG", target = null,
            trigger = "点击提交作业", severity = "S3").copy(symptom = "点击提交后无法完成提交")
        val evidence = maker.evaluate(
            profile, cluster(module = "HOMEWORK", category = "BUG", symptom = "提交作业后页面一直转圈",
                target = null, trigger = "提交作业")
        )
        val result = enforceClusterNameCoverage(
            profile, ClusterDecisionPlan("AUTO_HIGH", evidence, listOf(evidence), "因果一致")
        )
        assertEquals("AUTO_HIGH", result.action)
        assertEquals(evidence.cluster.id, result.selected?.cluster?.id)
    }

    @Test
    fun `manual cluster name must describe the extracted primary problem`() {
        val network = profile(module = "DEVICE", category = "BUG", target = null, trigger = null, severity = "S3")
            .copy(symptom = "设备网络连接异常", failureModes = setOf("NETWORK"), informationQuality = "ACTIONABLE")

        assertTrue(clusterNameCoversProfile("设备网络连接异常", network))
        assertFalse(clusterNameCoversProfile("业务系统错误", network))
    }

    @Test
    fun `manual cluster name keeps trigger and stable signature boundaries visible`() {
        val crash = profile(
            module = "AI_TUTOR", category = "BUG", error = "NULLPOINTEREXCEPTION",
            target = "助教", trigger = "点击助教", severity = "S3"
        ).copy(symptom = "点击助教后应用闪退", failureModes = setOf("CRASH"),
            capability = "AI_TUTOR", scenarioOrAction = "CLICK", informationQuality = "ACTIONABLE")

        assertTrue(clusterNameCoversProfile("点击助教后应用闪退", crash))
        assertFalse(clusterNameCoversProfile("启动助教时应用闪退 NULLPOINTEREXCEPTION", crash))
    }

    @Test
    fun `split partition rejects every hard boundary conflict including after an empty first profile`() {
        val base = profile(
            module = "AI_TUTOR", category = "BUG", error = "NULLPOINTEREXCEPTION",
            target = "助教", trigger = "点击助教", severity = "S3"
        ).copy(failureModes = setOf("CRASH"), capability = "AI_TUTOR", scenarioOrAction = "CLICK")

        assertFalse(sameHardPartitionBoundary(listOf(base, base.copy(capability = "VIDEO_MEDIA"))))
        assertFalse(sameHardPartitionBoundary(listOf(base, base.copy(scenarioOrAction = "START"))))
        assertFalse(sameHardPartitionBoundary(listOf(base, base.copy(failureModes = setOf("NETWORK")))))
        assertFalse(sameHardPartitionBoundary(listOf(base, base.copy(errorSignature = "HTTP-500"))))
        assertFalse(sameHardPartitionBoundary(listOf(
            base.copy(capability = null, scenarioOrAction = null, errorSignature = null, failureModes = emptySet()),
            base,
            base.copy(scenarioOrAction = "START")
        )))
        assertTrue(sameHardPartitionBoundary(listOf(base, base.copy(productScope = "OTHER_APP", functionModule = "VIDEO"))))
    }

    @Test
    fun `exact generic signature with only one structural anchor and weak text stays new`() {
        val profile = profile(
            module = null, category = "BUG", error = "ANR", target = null, trigger = null, severity = "S3"
        ).copy(symptom = "网络连接反复断开")
        val evidence = maker.evaluate(
            profile,
            cluster(
                module = null, category = "BUG", error = "ANR", symptom = "设备无法充电",
                target = null, trigger = null
            )
        )

        assertEquals(setOf("ISSUE_CATEGORY", "ERROR_SIGNATURE"), evidence.coreMatches)
        assertTrue(evidence.lexicalScore < policy.reviewThreshold)
        assertEquals("NEW_CLUSTER", maker.decide(profile, listOf(evidence), policy).action)
    }

    @Test
    fun `cross channel fixture sequence is not treated as an error signature`() {
        val signature = ErrorSignatureNormalizer().normalize(
            "跨渠道模拟问题：AI助教打开后闪退-XC-03；记录编号 2。"
        )

        assertNull(signature)
    }

    @Test
    fun `rehearsal tracking marker is not treated as an error signature`() {
        assertNull(ErrorSignatureNormalizer().normalize(
            "REHEARSAL-20260805-211034-R2 健康趋势图空白"
        ))
        assertNull(ErrorSignatureNormalizer().normalize(
            "DEMO_20260805 绘画画布撤销异常"
        ))
    }

    @Test
    fun `real business error code remains an error signature`() {
        assertEquals("PAY-20260805", ErrorSignatureNormalizer().normalize(
            "支付服务返回 PAY-20260805，请稍后重试"
        ))
    }

    private fun profile(
        module: String? = "AI_TUTOR", category: String? = "BUG", error: String? = null,
        target: String? = "助教", trigger: String? = "点击AI助教", severity: String? = "S1"
    ) = ProblemProfile(
        productScope = "LEARNING_MACHINE", functionModule = module, issueCategory = category,
        problemType = "CRASH", symptom = "AI助教点击后闪退", targetObject = target, trigger = trigger,
        usageStage = "USE", errorSignature = error, appVersion = "3.2.1", platform = "course-app",
        deviceModel = "XT11A", severityLevel=severity,sourceChannel="SDK",
        aiConfidence = BigDecimal("0.95"), fieldSources = emptyMap()
    )

    private fun cluster(
        id: Long = 1, module: String? = "AI_TUTOR", category: String? = "BUG", error: String? = null,
        symptom: String = "AI助教点击后闪退", status: String = "ACTIVE", target: String? = "助教",
        trigger: String? = "点击AI助教"
    ) = Cluster(
        id = id, clusterName = "AI助教闪退", productScope = "LEARNING_MACHINE", functionModule = module,
        issueCategory = category, canonicalSymptom = symptom, canonicalObject = target,
        canonicalTrigger = trigger, errorSignature = error, clusterStatus = status
    )
}
