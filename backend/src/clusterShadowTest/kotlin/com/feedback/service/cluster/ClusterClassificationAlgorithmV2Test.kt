package com.feedback.service.cluster

import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.system.measureNanoTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ClusterClassificationAlgorithmV2Test {
    @Test
    fun `restricted content is terminal before application resolution and cannot retain manual cluster`() {
        val result = ClusterShadowClassifierV3().classify(listOf(
            ClusterClassificationInput(
                sourceId = 1,
                applicationKey = "audited-app",
                applicationName = "Audited App",
                title = null,
                description = null,
                contentDisposition = ShadowContentDisposition.MASKING_REVIEW_REQUIRED,
                manualConclusion = ManualClusterConclusion("manual:1", "existing cluster")
            )
        ))

        assertEquals(ProblemClassificationStatus.CONTENT_REVIEW_NEEDED, result.assignments.single().status)
        assertEquals(null, result.assignments.single().clusterKey)
        assertEquals("TERMINAL", result.assignments.single().decision)
    }

    @Test
    fun `cross channel merge uses only approved application identity`() {
        val sameAppStation = input(11, "app-identity-a", "学习机", "播放视频时卡住")
            .copy(sourceChannel = "STATION_FEEDBACK")
        val sameAppJira = input(12, "app-identity-a", "学习机", "video freezes during playback")
            .copy(sourceChannel = "JIRA")
        val otherApp = input(13, "app-identity-b", "另一应用", "播放视频时卡住")
            .copy(sourceChannel = "REDBLACK")

        val result = ClusterShadowClassifierV3().classify(listOf(sameAppStation, sameAppJira, otherApp))

        assertEquals(result.assignments[0].clusterKey, result.assignments[1].clusterKey)
        assertNotEquals(result.assignments[0].clusterKey, result.assignments[2].clusterKey)
        assertEquals(2, result.clusterCount)
    }

    @Test
    fun `channel project platform display and text evidence cannot become application`() {
        val extractor = AtomicProblemExtractorV3()
        val untrusted = ApplicationIdentityEvidence.entries.filterNot {
            it == ApplicationIdentityEvidence.APPROVED_IDENTITY
        }.mapIndexed { index, evidence ->
            extractor.extract(
                input(20L + index, "candidate", "display", "播放视频时卡住")
                    .copy(sourceChannel = "JIRA", applicationEvidence = evidence)
            )
        }
        val channelName = extractor.extract(
            input(30, "JIRA", "display", "播放视频时卡住").copy(sourceChannel = "JIRA")
        )

        assertTrue((untrusted + channelName).all {
            it.status == ProblemClassificationStatus.APPLICATION_UNRESOLVED && it.identityKey == null
        })
    }

    @Test
    fun `语义输入剥离 SDK 技术元数据和轨迹附件`() {
        assertEquals(
            "加载太慢了",
            ClusterSemanticTextV3.sanitizeClause(
                "加载太慢了{\"appId\":\"10273\",\"sessionId\":\"private-session\"}"
            )
        )
        assertEquals(
            "我写对了",
            ClusterSemanticTextV3.sanitizeClause("我写对了 书写轨迹:https://private.example/path 埋点code: 0")
        )
        assertEquals(
            "题目不匹配",
            ClusterSemanticTextV3.sanitizeClause("题目不匹配{chapterId: private, questionId: private}")
        )
        assertEquals("音频没声音", ClusterSemanticTextV3.sanitizeClause("音频没声音{}"))
    }

    @Test
    fun `真实高余弦对抗案例按症状方向和判定阶段拆分`() {
        val extractor = AtomicProblemExtractorV3()
        assertEquals(
            "WRITING_FALSE_ACCEPT",
            extractor.extract(input(901, "alpha", "同步学", "错了居然说对了 书写轨迹:https://private.invalid/a")).failureMode
        )
        assertEquals(
            "WRITING_FALSE_REJECT",
            extractor.extract(input(902, "alpha", "同步学", "我写的是对的,他说是错的 书写轨迹:https://private.invalid/b")).failureMode
        )
        assertEquals(
            "QUESTION_RECOGNITION_ERROR",
            extractor.extract(input(903, "alpha", "题目答疑", "题目识别的不对")).failureMode
        )
        assertEquals(
            "ANSWER_CONTENT_ERROR",
            extractor.extract(input(904, "alpha", "题目答疑", "答案错误")).failureMode
        )
        assertEquals(
            "AUDIO_GRADING_ERROR",
            extractor.extract(input(905, "alpha", "随堂学", "照着你们这个音频读的还说错")).failureMode
        )
        assertEquals(
            "MEDIA_ERROR",
            extractor.extract(input(906, "alpha", "随堂学", "它本来的音频有错误")).failureMode
        )
        assertEquals(
            "WRITING_PRONUNCIATION_ERROR",
            extractor.extract(input(907, "alpha", "同步学", "读音错了 书写轨迹:https://private.invalid/c")).failureMode
        )
        assertEquals(
            "GRADING_ERROR",
            extractor.extract(input(908, "alpha", "题目答疑", "我按照答案写的,他却说我错了")).failureMode
        )
    }

    @Test
    fun `后校准真实困难负例按直接症状拆分而不是被宽泛标签合并`() {
        val extractor = AtomicProblemExtractorV3()
        val network = extractor.extract(input(920, "alpha", "同步学", "网络不好"))
        val writing = extractor.extract(input(921, "alpha", "同步学", "不要变成红色的错字 书写轨迹:[URL]"))
        val readAloud = extractor.extract(input(922, "alpha", "题目答疑", "算式朗读有错"))
        val answerContent = extractor.extract(input(923, "alpha", "题目答疑", "答案错误"))

        assertEquals("NETWORK", network.failureMode)
        assertEquals("WRITING_FALSE_REJECT", writing.failureMode)
        assertEquals("READ_ALOUD_CONTENT_ERROR", readAloud.failureMode)
        assertEquals("ANSWER_CONTENT_ERROR", answerContent.failureMode)
        listOf(
            network to writing,
            readAloud to answerContent
        ).forEach { (left, right) ->
            assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(left, right))
        }
    }

    @Test
    fun `shadow11 将宽泛内容书写和讲解错误拆为可审计症状边界`() {
        val extractor = AtomicProblemExtractorV3()
        val cases = listOf(
            "答案有错误" to "ANSWER_CONTENT_ERROR",
            "答案明明是对的却给我判错" to "GRADING_ERROR",
            "讲的是另一道题不是这个" to "QUESTION_MISMATCH",
            "题目的单词输入错误" to "QUESTION_INPUT_ERROR",
            "不是答案错是过程错了" to "PROCESS_ERROR",
            "每次给的答案都不一样" to "ANSWER_VARIANCE",
            "算式有错误" to "EXPRESSION_ERROR",
            "讲解方法有错误" to "EXPLANATION_METHOD_ERROR",
            "讲题总结答案有误" to "EXPLANATION_RESULT_ERROR"
        ).mapIndexed { index, (text, mode) ->
            extractor.extract(input(1_000L + index, "alpha", "题目答疑", text)) to mode
        }
        cases.forEach { (profile, expected) -> assertEquals(expected, profile.failureMode) }
        val profiles = cases.map { it.first }
        listOf(0 to 1, 0 to 2, 0 to 3, 0 to 4, 0 to 5, 0 to 6, 7 to 8).forEach { (left, right) ->
            assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(profiles[left], profiles[right]))
        }
    }

    @Test
    fun `宽泛模式或单侧缺失字段的高余弦只能进入 provisional`() {
        val broadLeft = semanticProfile(1, "a", "题目内容有误", target = "题目", failureMode = "CONTENT_ERROR")
        val broadRight = semanticProfile(2, "b", "内容看起来不对", target = "题目", failureMode = "CONTENT_ERROR")
        val targetMissing = semanticProfile(3, "c", "无法下载", failureMode = "DOWNLOAD")
        val targetKnown = semanticProfile(4, "d", "无法下载游戏", target = "游戏", failureMode = "DOWNLOAD")
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })

        val broad = recall.recall(broadRight, listOf(broadLeft), 20).single()
        assertEquals(SemanticCandidateJudgment.PROVISIONAL, broad.semanticJudgment)
        assertEquals(SemanticAcceptEligibilityReason.BROAD_FAILURE_MODE, broad.semanticEligibilityReason)

        val missing = recall.recall(targetKnown, listOf(targetMissing), 20).single()
        assertEquals(SemanticCandidateJudgment.PROVISIONAL, missing.semanticJudgment)
        assertEquals(SemanticAcceptEligibilityReason.TARGET_ONE_SIDED_MISSING, missing.semanticEligibilityReason)
    }

    @Test
    fun `书写误拒绝短表述与下载口语动作仍形成具体可审计边界`() {
        val extractor = AtomicProblemExtractorV3()
        listOf(
            "正常写字他就说错 书写轨迹:[URL]",
            "字不好看它就说做错了 书写轨迹:[URL]",
            "他给我批错 书写轨迹:[URL]",
            "不要变成红色的错字 书写轨迹:[URL]"
        ).forEachIndexed { index, text ->
            assertEquals(
                "WRITING_FALSE_REJECT",
                extractor.extract(input(1_100L + index, "alpha", "同步学", text)).failureMode
            )
        }
        assertEquals(
            "DOWNLOAD",
            extractor.extract(input(1_200, "alpha", "应用下载", "为啥不能下游戏")).action
        )
        val classroom = extractor.extract(input(1_201, "alpha", "应用下载", "伴学课堂APP无法下载"))
        val babyClassroom = extractor.extract(input(1_202, "alpha", "应用下载", "宝宝课堂App下载不了"))
        assertEquals("伴学课堂", classroom.target)
        assertEquals("宝宝课堂", babyClassroom.target)
        assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(classroom, babyClassroom))
    }

    private val classifier = ClusterShadowClassifierV2()

    @Test
    fun `黄金集回放提升成对质量并清除低信息污染`() {
        val cases = goldCases()
        val optimized = classifier.classify(cases.map(GoldCase::input))
        val optimizedKeys = optimized.assignments.associate { it.sourceId to it.clusterKey }
        val legacyKeys = cases.associate { it.input.sourceId to legacyKey(it.input) }
        val expected = cases.associate { it.input.sourceId to it.expectedCluster }

        val before = pairMetrics(expected, legacyKeys)
        val after = pairMetrics(expected, optimizedKeys)

        assertTrue(after.precision > before.precision, "precision: $before -> $after")
        assertTrue(after.recall >= before.recall, "recall: $before -> $after")
        assertEquals(1.0, after.precision, 0.000001)
        assertEquals(1.0, after.recall, 0.000001)
        assertEquals(0, optimized.assignments.count {
            it.status != ProblemClassificationStatus.ACTIONABLE && it.clusterKey != null
        })
        assertEquals(cases.count { it.expectedCluster == null }, optimized.nonActionableCount)

        println("CLUSTER_SHADOW_BASELINE cases=${cases.size} precision=${before.precision.format()} " +
            "recall=${before.recall.format()} f1=${before.f1.format()} clusters=${legacyKeys.values.toSet().size}")
        println("CLUSTER_SHADOW_OPTIMIZED cases=${cases.size} precision=${after.precision.format()} " +
            "recall=${after.recall.format()} f1=${after.f1.format()} clusters=${optimized.clusterCount} " +
            "terminal=${optimized.nonActionableCount}")
    }

    @Test
    fun `相同现象按应用隔离且不同原子问题必须拆分`() {
        val extracted = AtomicProblemExtractorV2()
        val alphaFreeze = extracted.extract(input(1, "alpha", "学习机", "播放视频时卡住"))
        val betaFreeze = extracted.extract(input(2, "beta", "课堂屏", "播放视频时卡住"))
        val alphaAudio = extracted.extract(input(3, "alpha", "学习机", "播放视频没有声音"))

        assertNotEquals(alphaFreeze.identityKey, betaFreeze.identityKey)
        assertNotEquals(alphaFreeze.identityKey, alphaAudio.identityKey)
        assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(alphaFreeze, betaFreeze))
        assertEquals(BoundaryEvidence.CONFLICT, ProblemBoundaryPolicyV2().compare(alphaFreeze, alphaAudio))
    }

    @Test
    fun `同一应用内的同义和多语言表达应合并`() {
        val result = classifier.classify(listOf(
            input(1, "alpha", "学习机", "播放视频时卡住"),
            input(2, "alpha", "学习机", "video freezes during playback"),
            input(3, "alpha", "学习机", "播放 video 一直很卡")
        ))
        assertEquals(1, result.clusterCount)
        assertEquals(setOf("LINK_EXISTING", "NEW_CLUSTER"), result.assignments.map { it.decision }.toSet())
    }

    @Test
    fun `标题正文重复和可选动作缺失不应制造碎簇`() {
        val duplicated = input(1, "alpha", "学习机", "播放视频时一直很卡")
            .copy(title = "视频卡顿")
        val result = classifier.classify(listOf(
            duplicated,
            input(2, "alpha", "学习机", "视频卡顿")
        ))

        assertEquals(1, result.clusterCount)
        assertTrue(result.assignments.all { it.status == ProblemClassificationStatus.ACTIONABLE })
        assertEquals(setOf("NEW_CLUSTER", "LINK_EXISTING"), result.assignments.map { it.decision }.toSet())
    }

    @Test
    fun `因果句提取结果症状而不是误判为多问题`() {
        val profile = AtomicProblemExtractorV2().extract(
            input(1, "alpha", "学习机", "网络异常导致视频无法播放")
        )

        assertEquals(ProblemClassificationStatus.ACTIONABLE, profile.status)
        assertEquals("PLAYBACK", profile.failureMode)
        assertEquals("视频", profile.target)
    }

    @Test
    fun `词典未覆盖与真正低信息必须分开`() {
        val extractor = AtomicProblemExtractorV2()

        assertEquals(ProblemClassificationStatus.NOVEL_SYMPTOM,
            extractor.extract(input(1, "alpha", "学习机", "页面排版错位")).status)
        assertEquals(ProblemClassificationStatus.INSUFFICIENT_INFORMATION,
            extractor.extract(input(2, "alpha", "学习机", "帮忙看看")).status)
    }

    @Test
    fun `绘本音视频题目解析和书写轨迹应自动识别无需人工状态`() {
        val extractor = AtomicProblemExtractorV2()
        val cases = listOf(
            input(1, "ai", "AI学伴", "其他。糖盒的数不是10273，source:题目解析") to "CONTENT_ERROR",
            input(2, "book", "绘本伴读", "绘本无法识别。百变马丁") to "BOOK_RECOGNITION_FAILURE",
            input(3, "study", "随堂学", "同步学(音视频错误)。chapterId:7199") to "MEDIA_ERROR",
            input(4, "study", "随堂学", "同步学其他错误。不好，书写轨迹:[URL]") to "WRITING_TRAJECTORY_ERROR"
        )

        cases.forEach { (sample, expectedMode) ->
            val profile = extractor.extract(sample)
            assertEquals(ProblemClassificationStatus.ACTIONABLE, profile.status, sample.description)
            assertEquals(expectedMode, profile.failureMode, sample.description)
        }
    }

    @Test
    fun `应用缺失下载加载识别和课程缺失等通用反馈应自动分类`() {
        val extractor = AtomicProblemExtractorV2()
        val cases = listOf(
            input(11, "store", "应用商店", "缺少宝宝巴士数学") to "APP_MISSING",
            input(12, "store", "应用商店", "这个下载成果根本下不了游戏") to "DOWNLOAD",
            input(13, "store", "应用商店", "网络不行，加载了好久都没有好") to "LOADING_SLOW",
            input(14, "study", "随堂学", "同步学识别不准确") to "RECOGNITION_ERROR",
            input(15, "course", "知识课程", "这个课少了几节") to "CONTENT_MISSING",
            input(16, "qa", "题目答疑", "没告诉我算式啊，我咋写") to "EXPLANATION_INCOMPLETE",
            input(17, "store", "应用商店", "权限到底在哪里给") to "PERMISSION_ENTRY_UNCLEAR",
            input(18, "ai", "AI学伴", "能不能讲快一点儿，咋这么多解析") to "RESPONSE_TOO_VERBOSE",
            input(19, "study", "随堂学", "同步学显示错误") to "DISPLAY_ERROR",
            input(20, "store", "应用商店", "退的时候那一个字好像不掉") to "UI_CONTROL_FAILURE",
            input(21, "qa", "题目答疑", "a等于四，结果你告诉我a等于一") to "CONTENT_ERROR",
            input(22, "store", "应用商店", "下载了还不能使用") to "POST_DOWNLOAD_USAGE_FAILURE"
        )

        cases.forEach { (sample, expectedMode) ->
            val profile = extractor.extract(sample)
            assertEquals(ProblemClassificationStatus.ACTIONABLE, profile.status, sample.description)
            assertEquals(expectedMode, profile.failureMode, sample.description)
        }
    }

    @Test
    fun `模板标题不能阻止单字和随机英文进入信息不足`() {
        val extractor = AtomicProblemExtractorV2()
        listOf(
            "应用程序。任" to ProblemClassificationStatus.INSUFFICIENT_INFORMATION,
            "其他错误。?" to ProblemClassificationStatus.INSUFFICIENT_INFORMATION,
            "其他问题。hjgtyftftuutyffhgfhfgfg" to ProblemClassificationStatus.NOISE
        ).forEachIndexed { index, (text, expected) ->
            assertEquals(
                expected,
                extractor.extract(input(30L + index, "app", "应用", text)).status,
                text
            )
        }
    }

    @Test
    fun `来源标签与用户正文分层且冲突时正文优先`() {
        val extractor = AtomicProblemExtractorV3()
        val sourceOnly = input(40, "qa", "题目答疑", "不聪明").copy(title = "讲题思路错误")
        val sourceProfile = extractor.extract(sourceOnly)
        assertEquals(ProblemClassificationStatus.ACTIONABLE, sourceProfile.status)
        assertEquals(ProblemEvidenceSource.SOURCE_LABEL, sourceProfile.evidenceSource)

        val conflict = input(41, "study", "随堂学", "书写轨迹错误").copy(title = "音视频错误")
        val conflictProfile = extractor.extract(conflict)
        assertEquals(ProblemClassificationStatus.ACTIONABLE, conflictProfile.status)
        assertEquals("WRITING_TRAJECTORY_ERROR", conflictProfile.failureMode)
        assertEquals(ProblemEvidenceSource.DIRECT_TEXT, conflictProfile.evidenceSource)
    }

    @Test
    fun `非故障噪声历史信息损失与不完整意图应自动进入不同终态`() {
        val extractor = AtomicProblemExtractorV3()
        val cases = listOf(
            "hjgtyftftuutyffhgfhfgfg" to ProblemClassificationStatus.NOISE,
            "我的笔不见了，我觉得就是AI偷的" to ProblemClassificationStatus.NON_FAULT_INPUT,
            "不知道{\"fileKey\":\"[TECH_ID]\",\"questionInfo\":\"[URL]\"}" to ProblemClassificationStatus.LEGACY_INFORMATION_LOSS,
            "我想换" to ProblemClassificationStatus.USER_INTENT_INCOMPLETE
        )
        cases.forEachIndexed { index, (text, expected) ->
            assertEquals(expected, extractor.extract(input(50L + index, "app", "应用", text)).status, text)
        }
    }

    @Test
    fun `下载来源标签不得把家长管理和应用目录诉求伪装成故障`() {
        val extractor = AtomicProblemExtractorV3()
        val cases = listOf(
            "不能自己下载，家长管理，我想自己管理",
            "能不能多来点游戏应用的啊",
            "添加游戏中心，并且里面下载游戏",
            "为什么没有我要的程序，应该把所有程序都录入",
            "只能弄有益的，不能弄游戏"
        )

        cases.forEachIndexed { index, description ->
            val profile = extractor.extract(
                input(9_000L + index, "store", "应用商店", description).copy(title = "应用程序下载")
            )
            assertEquals(ProblemClassificationStatus.NON_FAULT_INPUT, profile.status)
            assertEquals(null, profile.identityKey)
        }
        assertEquals(
            ProblemClassificationStatus.ACTIONABLE,
            extractor.extract(input(9_100, "store", "应用商店", "增加应用后下载失败")).status
        )
    }

    @Test
    fun `绘本识别错误优先于通用手写识别模式`() {
        val extractor = AtomicProblemExtractorV3()
        listOf("绘本无法识别", "绘本识别有误", "绘本识别有错").forEachIndexed { index, description ->
            val profile = extractor.extract(input(9_200L + index, "reader", "绘本伴读", description))
            assertEquals(ProblemClassificationStatus.ACTIONABLE, profile.status)
            assertEquals("BOOK_RECOGNITION_FAILURE", profile.failureMode)
            assertEquals("绘本", profile.target)
        }
    }

    @Test
    fun `仅来源标签证据与候选竞争都创建临时候选簇而非等待人工`() {
        val sourceOnly = input(60, "qa", "题目答疑", "不聪明").copy(title = "讲题思路错误")
        val assignment = ClusterShadowClassifierV2().classify(listOf(sourceOnly)).assignments.single()
        assertEquals("PROVISIONAL_NEW_CLUSTER", assignment.decision)
        assertTrue(assignment.clusterKey != null)

        val genericMissing = input(61, "store", "应用商店", "155412576ffhhcgg").copy(title = "应用缺失")
        val genericAssignment = ClusterShadowClassifierV2().classify(listOf(genericMissing)).assignments.single()
        assertEquals(ProblemClassificationStatus.NOISE, genericAssignment.status)
        assertEquals("TERMINAL", genericAssignment.decision)
    }

    @Test
    fun `簇级冲突防止缺失字段样本传递合并`() {
        val result = classifier.classify(listOf(
            input(1, "alpha", "学习机", "打开视频时卡顿"),
            input(2, "alpha", "学习机", "视频卡顿"),
            input(3, "alpha", "学习机", "播放视频时卡顿")
        ))

        assertEquals(2, result.clusterCount)
        val keys = result.assignments.associate { it.sourceId to it.clusterKey }
        assertNotEquals(keys.getValue(1), keys.getValue(3))
    }

    @Test
    fun `并列高分候选竞争时应创建隔离临时候选簇`() {
        val profiles = mapOf(
            1L to profile(1, "a", "ERR-A"),
            2L to profile(2, "b", "ERR-B"),
            3L to profile(3, "z", null)
        )
        val guarded = ClusterShadowClassifierV2(extractor = AtomicProblemExtraction {
            profiles.getValue(it.sourceId)
        })
        val result = guarded.classify((1L..3L).map { input(it, "alpha", "学习机", "placeholder") })

        val provisional = result.assignments.single { it.sourceId == 3L }
        assertEquals("PROVISIONAL_NEW_CLUSTER", provisional.decision)
        assertTrue(provisional.clusterKey != null)
        assertEquals(3, result.clusterCount)
    }

    @Test
    fun `空噪声模板短文本和歧义内容进入非业务终态`() {
        val inputs = listOf(
            input(1, "alpha", "学习机", ""),
            input(2, "alpha", "学习机", "【SDK】用户反馈：测试"),
            input(3, "alpha", "学习机", "%%%%%%%%%%%%"),
            input(4, "alpha", "学习机", "帮忙看看"),
            input(5, "alpha", "学习机", "视频卡顿，但是麦克风没有声音"),
            input(6, null, null, "播放视频时卡顿")
        )
        val result = classifier.classify(inputs)
        assertEquals(0, result.clusterCount)
        assertEquals(6, result.nonActionableCount)
        assertEquals(ProblemClassificationStatus.MULTI_PROBLEM_AMBIGUOUS,
            result.assignments.single { it.sourceId == 5L }.status)
        assertEquals(ProblemClassificationStatus.APPLICATION_UNRESOLVED,
            result.assignments.single { it.sourceId == 6L }.status)
    }

    @Test
    fun `人工结论优先且确定性重跑不漂移`() {
        val locked = input(1, "alpha", "学习机", "播放视频时卡顿").copy(
            manualConclusion = ManualClusterConclusion("manual:42", "人工确认的视频问题")
        )
        val inputs = goldCases().take(20).map(GoldCase::input) + locked.copy(sourceId = 10_001)
        val first = classifier.classify(inputs).assignments.associateBy(ShadowClusterAssignment::sourceId)
        repeat(20) { seed ->
            val shuffled = inputs.shuffled(Random(seed))
            val rerun = classifier.classify(shuffled).assignments.associateBy(ShadowClusterAssignment::sourceId)
            assertEquals(first, rerun)
        }
        val assignment = first.getValue(10_001)
        assertTrue(assignment.manualLocked)
        assertEquals("manual:42", assignment.clusterKey)
        assertEquals("MANUAL_LOCKED", assignment.decision)
    }

    @Test
    fun `候选召回有界稳定并按应用过滤`() {
        val extractor = AtomicProblemExtractorV2()
        val query = extractor.extract(input(999, "alpha", "学习机", "播放视频时卡住"))
        val candidates = (1L..80L).map { id ->
            extractor.extract(input(id, if (id % 2L == 0L) "alpha" else "beta",
                if (id % 2L == 0L) "学习机" else "课堂屏", "播放视频时卡顿"))
        }
        val recalled = ClusterCandidateRecallV2().recall(query, candidates, topK = 20)
        assertEquals(20, recalled.size)
        assertTrue(recalled.all { it.profile.applicationKey == "alpha" })
        assertEquals(recalled.sortedWith(compareByDescending<ShadowCandidate>(ShadowCandidate::score)
            .thenBy { it.profile.identityKey }.thenBy { it.profile.sourceId }), recalled)
    }

    @Test
    fun `候选数量和重复 source 使用统一请求异常`() {
        val extractor = AtomicProblemExtractorV3()
        val query = extractor.extract(input(999, "alpha", "学习机", "播放视频时卡住"))
        val deterministic = assertThrows(ClientException::class.java) {
            ClusterCandidateRecallV2().recall(query, emptyList(), 0)
        }
        assertEquals("CLUSTER_SHADOW_TOP_K_OUT_OF_RANGE", deterministic.reasonCode)

        val semantic = assertThrows(ClientException::class.java) {
            SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })
                .recall(query, emptyList(), 51)
        }
        assertEquals("CLUSTER_SHADOW_TOP_K_OUT_OF_RANGE", semantic.reasonCode)

        val duplicate = input(1, "alpha", "学习机", "播放视频时卡住")
        val duplicated = assertThrows(ClientException::class.java) {
            ClusterShadowClassifierV3().classify(listOf(duplicate, duplicate.copy(description = "播放视频时卡顿")))
        }
        assertEquals("CLUSTER_SHADOW_SOURCE_DUPLICATED", duplicated.reasonCode)
    }

    @Test
    fun `语义策略配置违规使用统一不变量异常和精确原因码`() {
        val invalidPolicies = listOf<Pair<String, () -> Unit>>(
            "CLUSTER_SEMANTIC_ACCEPT_THRESHOLD_INVALID" to {
                SemanticRecallPolicyV3(acceptThreshold = BigDecimal("1.01"))
            },
            "CLUSTER_SEMANTIC_PROVISIONAL_THRESHOLD_INVALID" to {
                SemanticRecallPolicyV3(provisionalThreshold = BigDecimal("0.83"))
            },
            "CLUSTER_SEMANTIC_WEIGHT_INVALID" to {
                SemanticRecallPolicyV3(semanticWeight = BigDecimal("-0.01"))
            },
            "CLUSTER_SEMANTIC_TIMEOUT_INVALID" to {
                SemanticRecallPolicyV3(totalTimeoutMillis = 0)
            },
            "CLUSTER_SEMANTIC_CANDIDATE_LIMIT_INVALID" to {
                SemanticRecallPolicyV3(maxSemanticCandidates = 201)
            }
        )

        invalidPolicies.forEach { (reasonCode, construct) ->
            val error = assertThrows(ServiceException::class.java, construct)
            assertEquals(reasonCode, error.reasonCode)
        }
    }

    @Test
    fun `分类输入迭代期间画像消失时返回统一不变量异常`() {
        val retained = input(1, "alpha", "学习机", "播放视频时卡顿")
        val missing = input(2, "alpha", "学习机", "播放视频时卡顿")
        val inputs = IterationPhasedCollection(
            listOf(retained),
            listOf(retained),
            listOf(missing)
        )

        val error = assertThrows(ServiceException::class.java) {
            ClusterShadowClassifierV3().classify(inputs)
        }

        assertEquals("CLUSTER_SHADOW_PROFILE_MISSING", error.reasonCode)
    }

    @Test
    fun `分类输入迭代期间可执行项未建簇时返回统一不变量异常`() {
        val actionable = input(1, "alpha", "学习机", "播放视频时卡顿")
        val inputs = IterationPhasedCollection(
            listOf(actionable),
            listOf(actionable),
            emptyList(),
            listOf(actionable)
        )

        val error = assertThrows(ServiceException::class.java) {
            ClusterShadowClassifierV3().classify(inputs)
        }

        assertEquals("CLUSTER_SHADOW_ASSIGNMENT_MISSING", error.reasonCode)
    }

    @Test
    fun `重复导入收敛且分数边界明确`() {
        val extractor = AtomicProblemExtractorV2()
        val first = extractor.extract(input(1, "alpha", "学习机", "播放视频时卡住"))
        val duplicate = extractor.extract(input(2, "alpha", "学习机", "播放视频时卡住"))
        val different = extractor.extract(input(3, "alpha", "学习机", "播放视频没有声音"))
        val recall = ClusterCandidateRecallV2()

        assertEquals(first.identityKey, duplicate.identityKey)
        assertEquals("0.900000", recall.similarity(first, duplicate).toPlainString())
        assertTrue(recall.similarity(first, different) < "0.850000".toBigDecimal())
        assertEquals(2, classifier.classify(listOf(
            input(1, "alpha", "学习机", "播放视频时卡住"),
            input(2, "alpha", "学习机", "播放视频时卡住"),
            input(3, "alpha", "学习机", "播放视频没有声音")
        )).clusterCount)
    }

    @Test
    fun `语义召回可合并同故障模式的不同表述`() {
        val first = semanticProfile(1, "a", "识别结果与书写内容不一致", target = "手写字符")
        val query = semanticProfile(2, "z", "扫出来的不是我写的字", target = "手写字符")
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })

        val candidate = recall.recall(query, listOf(first), 20).single()

        assertEquals(BoundaryEvidence.MATCH, candidate.boundary)
        assertEquals(SemanticCandidateJudgment.ACCEPT, candidate.semanticJudgment)
        assertTrue(candidate.score >= "0.700000".toBigDecimal())
        val result = ClusterShadowClassifierV3(
            extractor = AtomicProblemExtraction { if (it.sourceId == 1L) first else query },
            recall = recall
        ).classify(listOf(input(1, "alpha", "学习机", "x"), input(2, "alpha", "学习机", "y")))
        assertEquals(1, result.clusterCount)
    }

    @Test
    fun `校准权重不允许仅故障模式相同的候选被语义分越界合并`() {
        val first = semanticProfile(1, "a", "识别结果与书写内容不一致")
        val query = semanticProfile(2, "z", "扫出来的不是我写的字")
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })

        val candidate = recall.recall(query, listOf(first), 20).single()
        assertEquals(SemanticCandidateJudgment.ACCEPT, candidate.semanticJudgment)
        assertEquals("0.650000", candidate.score.toPlainString())
        val result = ClusterShadowClassifierV3(
            extractor = AtomicProblemExtraction { if (it.sourceId == 1L) first else query },
            recall = recall
        ).classify(listOf(input(1, "alpha", "学习机", "x"), input(2, "alpha", "学习机", "y")))
        assertEquals(2, result.clusterCount)
    }

    @Test
    fun `中等语义置信度创建临时候选簇而不等待人工`() {
        val first = semanticProfile(1, "a", "识别结果错误")
        val query = semanticProfile(2, "z", "扫描出来并非这个字")
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { text ->
            if (text.contains("扫描")) listOf(0.72f, 0.69f) else listOf(1f, 0f)
        })
        val result = ClusterShadowClassifierV3(
            extractor = AtomicProblemExtraction { if (it.sourceId == 1L) first else query },
            recall = recall
        ).classify(listOf(input(1, "alpha", "学习机", "x"), input(2, "alpha", "学习机", "y")))

        assertEquals(2, result.clusterCount)
        assertEquals("PROVISIONAL_NEW_CLUSTER", result.assignments.single { it.sourceId == 2L }.decision)
    }

    @Test
    fun `语义判定不得突破应用故障对象和错误签名边界`() {
        val base = semanticProfile(1, "a", "视频打不开 E100", target = "视频", failureMode = "NAVIGATION", signature = "E100")
        val conflicts = listOf(
            semanticProfile(2, "b", "视频打不开", applicationKey = "beta", target = "视频", failureMode = "NAVIGATION"),
            semanticProfile(3, "c", "视频无声", target = "视频", failureMode = "NO_AUDIO"),
            semanticProfile(4, "d", "课程打不开", target = "课程", failureMode = "NAVIGATION"),
            semanticProfile(5, "e", "视频打不开 E200", target = "视频", failureMode = "NAVIGATION", signature = "E200")
        )
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })

        conflicts.forEach { conflict ->
            assertTrue(recall.recall(conflict, listOf(base), 20).isEmpty(), conflict.primaryClause)
        }
    }

    @Test
    fun `语义模型异常空向量维度错误和超时均精确降级`() {
        val first = semanticProfile(1, "a", "识别结果错误")
        val query = semanticProfile(2, "z", "扫描结果错误")
        val deterministic = ClusterCandidateRecallV2()
        val expected = deterministic.recall(query, listOf(first), 20)
        val broken = listOf<SemanticEmbeddingPort>(
            SemanticEmbeddingPort { error("model unavailable") },
            SemanticEmbeddingPort { emptyList() },
            SemanticEmbeddingPort { text -> if (text.contains("扫描")) listOf(1f) else listOf(1f, 0f) },
            SemanticEmbeddingPort { Thread.sleep(100); listOf(1f, 0f) }
        )

        broken.forEachIndexed { index, port ->
            val policy = if (index == broken.lastIndex) SemanticRecallPolicyV3(totalTimeoutMillis = 5)
                else SemanticRecallPolicyV3()
            assertEquals(expected, SemanticCandidateRecallV3(port, policy = policy).recall(query, listOf(first), 20))
        }
    }

    @Test
    fun `新增识别同义表达可自动提取为同一症状`() {
        val extractor = AtomicProblemExtractorV3()
        val modes = listOf("识别不准确", "扫出来不是这个字", "认成别的字")
            .map { extractor.extract(input(it.hashCode().toLong(), "study", "随堂学", it)) }
        assertTrue(modes.all { it.status == ProblemClassificationStatus.ACTIONABLE })
        assertEquals(setOf("RECOGNITION_ERROR"), modes.mapNotNull { it.failureMode }.toSet())
        assertEquals(1, modes.mapNotNull { it.identityKey }.toSet().size)
    }

    @Test
    fun `语义增强在乱序和重复执行时保持稳定`() {
        val profiles = mapOf(
            1L to semanticProfile(1, "a", "识别结果与书写内容不一致", target = "手写字符"),
            2L to semanticProfile(2, "m", "机器把这个字识别错了", target = "手写字符"),
            3L to semanticProfile(3, "z", "扫出来的不是我写的字", target = "手写字符")
        )
        val classifier = ClusterShadowClassifierV3(
            extractor = AtomicProblemExtraction { profiles.getValue(it.sourceId) },
            recall = SemanticCandidateRecallV3(SemanticEmbeddingPort { listOf(1f, 0f) })
        )
        val inputs = (1L..3L).map { input(it, "alpha", "学习机", "placeholder") }
        val expected = classifier.classify(inputs)

        repeat(10) { seed -> assertEquals(expected, classifier.classify(inputs.shuffled(Random(seed)))) }
        assertEquals(1, expected.clusterCount)
    }

    @Test
    fun `语义向量按文本缓存避免候选重复推理`() {
        var calls = 0
        val first = semanticProfile(1, "a", "识别结果错误")
        val query = semanticProfile(2, "z", "扫描结果错误")
        val recall = SemanticCandidateRecallV3(SemanticEmbeddingPort {
            calls++
            listOf(1f, 0f)
        })

        recall.recall(query, listOf(first), 20)
        recall.similarity(query, first)
        recall.recall(query, listOf(first), 20)

        assertEquals(2, calls)
    }

    @Test
    fun `并发影子重跑和名称保持稳定`() {
        val inputs = goldCases().map(GoldCase::input)
        val expected = classifier.classify(inputs)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..32).map { Callable { classifier.classify(inputs.shuffled()) } })
            results.forEach { assertEquals(expected, it.get()) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue(expected.assignments.filter { it.clusterName != null }
            .all { it.clusterName!!.startsWith("学习机·") || it.clusterName!!.startsWith("课堂屏·") })
        assertTrue(expected.assignments.none { it.clusterName?.contains("待归纳") == true })
    }

    @Test
    fun `五千条记录回放性能保持有界`() {
        val base = goldCases().filter { it.input.applicationKey != null }
        val inputs = (1..5_000).map { index ->
            base[index % base.size].input.copy(sourceId = index.toLong(), manualConclusion = null)
        }
        var result: ShadowClusterResult? = null
        val elapsed = measureNanoTime { result = classifier.classify(inputs) }
        assertEquals(5_000, result!!.assignments.size)
        assertTrue(elapsed < 8_000_000_000L, "shadow replay took ${elapsed / 1_000_000} ms")
        println("CLUSTER_SHADOW_PERF records=5000 elapsed_ms=${elapsed / 1_000_000}")
    }

    private data class GoldCase(val input: ClusterClassificationInput, val expectedCluster: String?)
    private data class Metrics(val precision: Double, val recall: Double, val f1: Double)

    private fun goldCases(): List<GoldCase> {
        var id = 0L
        fun c(app: String?, appName: String?, text: String, expected: String?) =
            GoldCase(input(++id, app, appName, text), expected)
        return listOf(
            c("alpha", "学习机", "播放视频时卡住", "A_VIDEO_FREEZE"),
            c("alpha", "学习机", "视频播放一直很卡", "A_VIDEO_FREEZE"),
            c("alpha", "学习机", "video freezes during playback", "A_VIDEO_FREEZE"),
            c("beta", "课堂屏", "播放视频时卡住", "B_VIDEO_FREEZE"),
            c("beta", "课堂屏", "video freezes during playback", "B_VIDEO_FREEZE"),
            c("alpha", "学习机", "播放视频没有声音", "A_VIDEO_AUDIO"),
            c("alpha", "学习机", "video has no sound during playback", "A_VIDEO_AUDIO"),
            c("beta", "课堂屏", "播放视频没有声音", "B_VIDEO_AUDIO"),
            c("alpha", "学习机", "打开课程时应用崩溃", "A_COURSE_CRASH"),
            c("alpha", "学习机", "打开课程应用闪退", "A_COURSE_CRASH"),
            c("beta", "课堂屏", "打开课程时应用崩溃", "B_COURSE_CRASH"),
            c("alpha", "学习机", "提交作业失败", "A_HOMEWORK_SUBMIT"),
            c("alpha", "学习机", "作业无法完成提交", "A_HOMEWORK_SUBMIT"),
            c("alpha", "学习机", "提交作业后页面转圈导致无法完成提交", "A_HOMEWORK_SUBMIT"),
            c("alpha", "学习机", "错题本批改错误", "A_WRONGBOOK_GRADING"),
            c("alpha", "学习机", "错题本里的题判错了", "A_WRONGBOOK_GRADING"),
            c("alpha", "学习机", "AI助教识别有误", "A_TUTOR_RECOGNITION"),
            c("alpha", "学习机", "助教手写字符识别错了", "A_TUTOR_RECOGNITION"),
            c("alpha", "学习机", "设备断网无法联网", "A_NETWORK"),
            c("alpha", "学习机", "网络连接异常", "A_NETWORK"),
            c("beta", "课堂屏", "登录页面进不去", "B_LOGIN_NAV"),
            c("beta", "课堂屏", "无法进入登录", "B_LOGIN_NAV"),
            c("alpha", "学习机", "下载课程失败", "A_COURSE_DOWNLOAD"),
            c("alpha", "学习机", "课程无法下载", "A_COURSE_DOWNLOAD"),
            c("alpha", "学习机", "上传作业失败", "A_HOMEWORK_UPLOAD"),
            c("alpha", "学习机", "作业无法上传", "A_HOMEWORK_UPLOAD"),
            c("alpha", "学习机", "", null),
            c("alpha", "学习机", "测试", null),
            c("alpha", "学习机", "%%%%%%%%%%%%", null),
            c("alpha", "学习机", "帮忙看看", null),
            c("alpha", "学习机", "https://a.example https://b.example", null),
            c("alpha", "学习机", "abc123abc123abc123", null),
            c("alpha", "学习机", "视频卡顿，但是麦克风没有声音", null),
            c("alpha", "学习机", "打开课程闪退，同时作业上传失败", null),
            c("beta", "课堂屏", "视频黑屏，另外登录页面进不去", null),
            c(null, null, "播放视频时卡顿", null),
            c(null, null, "提交作业失败", null),
            c("alpha", "学习机", "点击AI助教后应用闪退", "A_TUTOR_CRASH"),
            c("alpha", "学习机", "点击助教后闪崩", "A_TUTOR_CRASH"),
            c("beta", "课堂屏", "点击AI助教后应用闪退", "B_TUTOR_CRASH")
        )
    }

    /** 复现 v1 的关键身份缺陷：缺少应用键、只取首个问题、噪声仍形成业务簇。 */
    private fun legacyKey(input: ClusterClassificationInput): String {
        val text = listOfNotNull(input.title, input.description).joinToString(" ")
        val failure = ClusterProblemOntology.primaryFailureMode(input.title, input.description) ?: "LOW_INFORMATION"
        val target = listOf("错题本", "助教", "题库", "作业", "视频", "课程", "麦克风", "摄像头", "登录", "支付")
            .firstOrNull(text::contains).orEmpty()
        return "v1:$target:$failure"
    }

    private fun pairMetrics(expected: Map<Long, String?>, actual: Map<Long, String?>): Metrics {
        var tp = 0; var fp = 0; var fn = 0
        val ids = expected.keys.sorted()
        for (left in ids.indices) for (right in left + 1 until ids.size) {
            val goldSame = expected[ids[left]] != null && expected[ids[left]] == expected[ids[right]]
            val predictedSame = actual[ids[left]] != null && actual[ids[left]] == actual[ids[right]]
            when {
                goldSame && predictedSame -> tp++
                !goldSame && predictedSame -> fp++
                goldSame -> fn++
            }
        }
        val precision = tp.toDouble() / (tp + fp).coerceAtLeast(1)
        val recall = tp.toDouble() / (tp + fn).coerceAtLeast(1)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        return Metrics(precision, recall, f1)
    }

    private fun Double.format() = "%.4f".format(this)

    /** 模拟调用方在分类执行期间改变可迭代视图，用于覆盖分类器的防御性一致性边界。 */
    private class IterationPhasedCollection<T>(
        private vararg val phases: List<T>
    ) : AbstractCollection<T>() {
        private var iteration = 0

        override val size: Int = phases.maxOfOrNull(List<T>::size) ?: 0

        override fun iterator(): Iterator<T> {
            val phase = phases.getOrElse(iteration) { phases.lastOrNull().orEmpty() }
            iteration += 1
            return phase.iterator()
        }
    }

    private fun input(id: Long, app: String?, appName: String?, text: String) = ClusterClassificationInput(
        sourceId = id, applicationKey = app, applicationName = appName, title = null, description = text
    )

    private fun profile(id: Long, identity: String, signature: String?) = AtomicProblemProfile(
        sourceId = id,
        applicationKey = "alpha",
        applicationName = "学习机",
        status = ProblemClassificationStatus.ACTIONABLE,
        primaryClause = "播放视频时卡顿",
        target = "视频",
        action = "PLAY",
        failureMode = "FREEZE",
        errorSignature = signature,
        canonicalSymptom = "使用视频时出现卡顿",
        identityKey = identity,
        reason = "test"
    )

    private fun semanticProfile(
        id: Long,
        identity: String,
        clause: String,
        applicationKey: String = "alpha",
        target: String? = null,
        failureMode: String = "RECOGNITION_ERROR",
        signature: String? = null
    ) = AtomicProblemProfile(
        sourceId = id,
        applicationKey = applicationKey,
        applicationName = "学习机",
        status = ProblemClassificationStatus.ACTIONABLE,
        primaryClause = clause,
        target = target,
        action = null,
        failureMode = failureMode,
        errorSignature = signature,
        canonicalSymptom = clause,
        identityKey = identity,
        reason = "test",
        evidenceSource = ProblemEvidenceSource.DIRECT_TEXT
    )
}
