package com.feedback.service.cluster

enum class ProblemSpecificity { A, B, C }

/** 内部问题指纹使用的带版本、确定性本体。 */
object ClusterProblemOntology {
    const val VERSION = "problem-ontology-v2-primary"
    private val MODES = linkedMapOf(
        "CRASH" to Regex("闪退|崩溃|crash", RegexOption.IGNORE_CASE),
        "FREEZE" to Regex("卡死|卡住|卡顿|一直.{0,4}卡|冻结|freeze", RegexOption.IGNORE_CASE),
        "NO_RESPONSE" to Regex("无响应|一直转圈|持续转圈|no response", RegexOption.IGNORE_CASE),
        "NO_AUDIO" to Regex("无声|没有声音|没声音|录音.{0,4}(?:无声|失败)", RegexOption.IGNORE_CASE),
        "RECOGNITION_ERROR" to Regex("识别(?:成|为).{1,12}|识别.{0,6}(?:错|错误|有误)|(?:错|错误)误识别|录错", RegexOption.IGNORE_CASE),
        "GRADING_ERROR" to Regex("判.{0,6}(?:错|错误|有误)|改错|批改.{0,4}(?:错|错误|有误)|结果错误|wrong result", RegexOption.IGNORE_CASE),
        "CONTENT_ERROR" to Regex("题目.{0,4}(?:错|错误|有误)|内容.{0,4}(?:错|错误|有误)|(?:答案|解析).{0,4}(?:错|错误|有误)", RegexOption.IGNORE_CASE),
        "PLAYBACK" to Regex("无法.{0,2}播放|播放失败|黑屏|playback", RegexOption.IGNORE_CASE),
        "NETWORK" to Regex("没有网络|无网络|断网|连不上网|无法联网|不能上网|上不了网|联网失败|网络.{0,6}(?:异常|失败|断开|不可用|不好|差)|连接.{0,4}(?:失败|断开)", RegexOption.IGNORE_CASE),
        "NAVIGATION" to Regex("跳不进去|进不去|无法进入|打开失败|打不开", RegexOption.IGNORE_CASE),
        "DOWNLOAD" to Regex("下载.{0,6}(?:失败|异常|不能|无法)", RegexOption.IGNORE_CASE),
        "UPLOAD" to Regex("上传.{0,6}(?:失败|异常|不能|无法)", RegexOption.IGNORE_CASE),
        "SUBMIT" to Regex("提交.{0,8}(?:失败|无法|不能)|无法完成提交", RegexOption.IGNORE_CASE)
    )

    fun failureModes(value: String): Set<String> = MODES.filterValues { it.containsMatchIn(value) }.keys

    /**
     * 一条反馈永远只对应一个问题。先从脱敏描述里挑出主失败，
     * 取不到再回退到标题。次要现象只作为证据存在，绝不
     * 新建 MAIN 关联，也不生成「等待拆分」的占位。
     */
    fun primaryFailureMode(title: String?, description: String?): String? =
        firstFailureMode(description) ?: firstFailureMode(title)

    private fun firstFailureMode(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return MODES.entries.mapIndexedNotNull { priority, (mode, regex) ->
            regex.find(value)?.let { Triple(it.range.first, priority, mode) }
        }.minWithOrNull(compareBy<Triple<Int, Int, String>> { it.first }.thenBy { it.second })?.third
    }

    fun hasCompetingFailureModes(value: String): Boolean {
        val modes = failureModes(value)
        return modes.size > 1 && modes != setOf("NO_RESPONSE", "SUBMIT")
    }

    fun targetClass(value: String?): String? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "课程" -> "COURSE_CONTAINER"
        "视频" -> "VIDEO_MEDIA"
        "助教" -> "AI_TUTOR"
        "题库" -> "QUESTION_BANK"
        "错题本" -> "WRONG_QUESTION_BOOK"
        "麦克风" -> "MICROPHONE"
        "摄像头" -> "CAMERA"
        "登录" -> "LOGIN"
        "支付" -> "PAYMENT"
        else -> value.trim().lowercase()
    }

    fun operationClass(value: String?): String? = when {
        value.isNullOrBlank() -> null
        value.contains("上传") -> "UPLOAD"
        value.contains("下载") -> "DOWNLOAD"
        value.contains("提交") -> "SUBMIT"
        value.contains("播放") -> "PLAY"
        value.contains("启动") -> "START"
        value.contains("点击") -> "CLICK"
        value.contains("进入") -> "ENTER"
        value.contains("打开") -> "OPEN"
        else -> null
    }
}

/** 让面向客户的簇名保持就事论事，且不回退到内部分类标签。 */
object ProductPhenomenonFormatter {
    private const val MAX_PHENOMENON_LENGTH = 200
    private const val PENDING = "问题现象待归纳"
    internal const val LOW_INFORMATION = "反馈内容不足，无法判断具体问题"
    private val PLACEHOLDER_TOKENS = setOf(
        "问题现象待归纳", "待归纳", "其他", "其它", "未知", "未分类", "测试", "恶", "占位置", "一",
        "other", "unknown", "test"
    )
    private val GENERIC_CHANNEL_LABELS = listOf(
        Regex("^(?:其他|其它)(?:错误|问题)$"),
        Regex("^.+[（(](?:其他|其它)(?:错误|问题)[）)]$"),
        Regex("^(?:识别有错误|应用程序|功能问题|功能建议)$")
    )
    private val LOW_INFORMATION_TEXT = listOf(
        Regex("^(?:非常|特别|超级|太|有一点点)*不好[。.!！]*$"),
        Regex("^(?:很差|太差|非常差|超级差|有一点点烦)[。.!！]*$"),
        Regex("^(?:对不起|告诉我答案|请小黑不要睡觉|太多学的了)[。.!！]*$")
    )
    private val PROBLEM_SIGNAL = Regex(
        "无法|不能|不可|失败|错误|有误|异常|闪退|崩溃|卡住|卡死|卡顿|冻结|无响应|转圈|干扰|" +
            "黑屏|白屏|花屏|不一致|缺失|缺少|找不到|没有|不见|打不开|进不去|跳不进去|" +
            "断开|断网|太慢|很慢|延迟|太小|太大|过高|过低|偏色|模糊|发热|耗电|噪音|抖动|" +
            "重启|丢失|重复|错位|遮挡|失效|失真|不准|不全|无声|没声音|破音|" +
            "不显示|不更新|不保存|不生效|不工作|不正确|不完整|不准确|不同步|不刷新|不跳转|不加载|" +
            "识别成|判错|改错|录错|只默认|建议|希望|想要|需要|申请|添加|新增|支持|移除|删除|取消",
        RegexOption.IGNORE_CASE
    )

    fun choose(
        canonical: String?,
        representative: String?,
        functionModule: String? = null,
        issueCategory: String? = null
    ): String {
        val candidates = listOf(canonical, representative).mapNotNull(::clean).filter { it.length <= 200 }
        candidates.firstOrNull { isDeterministicFallbackSentence(it) }?.let { return it }
        // 运维簇绝不能带着占位展示名被创建。来源抽取器通常会给出
        // 一句确定性的低信息量句子，但这里仍要对旧数据或字段不全的
        // 画像保持失效安全。
        return candidates.firstOrNull { isConcrete(it, functionModule, issueCategory) } ?: LOW_INFORMATION
    }

    fun fromSource(
        title: String?,
        description: String?,
        aiSummary: String?,
        problemType: String?,
        functionModule: String?,
        issueCategory: String?,
        targetObject: String? = null
    ): String {
        val cleanedTitle = clean(title)
        val cleanedDescription = clean(description)
        if (isObviousNoise(cleanedDescription)) return LOW_INFORMATION
        knownChannelSentence(cleanedTitle)?.let { return it }
        listOf(cleanedTitle, cleanedDescription, clean(aiSummary), clean(problemType))
            .firstNotNullOfOrNull { boundedConcretePhenomenon(it, functionModule, issueCategory) }
            ?.let { return it }
        normalizeShortProblemSentence(cleanedDescription)
            ?.takeIf { it != cleanedDescription && it.length <= MAX_PHENOMENON_LENGTH }
            ?.let { return it }
        return incompleteTargetSentence(targetObject) ?: LOW_INFORMATION
    }

    /** 通用分类标签与测试标签，不构成面向客户现象的证据。 */
    internal fun isPlaceholder(value: String?): Boolean {
        val cleaned = clean(value) ?: return true
        val tokens = cleaned.lowercase()
            .split(Regex("[\\s/·,，、;；|:_-]+"))
            .filter(String::isNotBlank)
        return (tokens.isNotEmpty() && tokens.all { it in PLACEHOLDER_TOKENS }) ||
            GENERIC_CHANNEL_LABELS.any { it.matches(cleaned) } ||
            cleaned == LOW_INFORMATION || cleaned.matches(Regex("^.+时出现异常，但反馈未说明具体表现$"))
    }

    internal fun isConcretePhenomenon(value: String?): Boolean = clean(value)?.let {
        it.length <= 200 && isConcrete(it, null, null) && !isPlaceholder(it) && !isGenericProblemText(it)
    } == true

    internal fun isLowInformation(value: String?) = clean(value) == LOW_INFORMATION
    internal fun specificity(value: String?): ProblemSpecificity {
        val text = clean(value) ?: return ProblemSpecificity.C
        if (isPlaceholder(text) || isLowInformation(text)) return ProblemSpecificity.C
        if (isGenericProblemText(text)) {
            return ProblemSpecificity.C
        }
        val hasScenario = Regex("点击|打开|进入|启动|上传|下载|提交|播放|录音|登录|支付|使用").containsMatchIn(text)
        val hasObject = Regex("助教|题库|错题本|课程|视频|麦克风|摄像头|作业|页面|应用|网络|屏幕|充电").containsMatchIn(text)
        val hasSpecificFailure = ClusterProblemOntology.failureModes(text).isNotEmpty() ||
            Regex("无法|不能|失败|黑屏|白屏|无声|没声音|转圈|闪退|卡死|卡顿|识别错误|判定错误|偏色|模糊|太小|太大|过高|过低|不一致|缺失|不显示|不更新").containsMatchIn(text)
        return when {
            hasSpecificFailure && (hasScenario || hasObject) -> ProblemSpecificity.A
            hasSpecificFailure -> ProblemSpecificity.B
            else -> ProblemSpecificity.C
        }
    }

    private fun isGenericProblemText(value: String) = value.matches(
        Regex("^(?:页面)?异常$|^操作失败$|^功能(?:不能用|无法使用|异常)$|^打开后(?:没反应|没有反应)$|^无法使用$")
    )

    /** 只有在新的句子明显更完整、且没有扩大问题范围时才演进。 */
    internal fun evolve(current: String?, incoming: String?): String? {
        val left = clean(current) ?: return null
        val right = clean(incoming) ?: return null
        if (left == right || left.length > 200 || right.length > 200) return null
        if (!isConcretePhenomenon(left) || !isConcretePhenomenon(right)) return null
        if (ClusterProblemOntology.hasCompetingFailureModes(right)) return null
        val leftModes = ClusterProblemOntology.failureModes(left)
        val rightModes = ClusterProblemOntology.failureModes(right)
        val causalSubmission = leftModes + rightModes == setOf("NO_RESPONSE", "SUBMIT")
        if (leftModes.isNotEmpty() && rightModes.isNotEmpty() && leftModes != rightModes && !causalSubmission) return null
        val leftNormalized = normalizeClusterSimilarityText(left)
        val rightNormalized = normalizeClusterSimilarityText(right)
        if (rightNormalized.contains(leftNormalized) && right.length > left.length) return right
        if (leftNormalized.contains(rightNormalized)) return null
        val bothSubmit = listOf(left, right).all { Regex("提交").containsMatchIn(it) }
        val combined = "$left；$right"
        if (bothSubmit && Regex("转圈").containsMatchIn(combined) && Regex("无法完成|不能完成|提交失败").containsMatchIn(combined)) {
            return "提交作业后页面持续转圈，导致作业无法完成提交"
        }
        return null
    }

    private fun isDeterministicFallbackSentence(value: String) =
        value == LOW_INFORMATION ||
            value.matches(Regex("^.+时出现异常，但反馈未说明具体表现$"))

    /** 为一条反馈中唯一的主问题给出稳定、面向客户的句子。 */
    fun forPrimaryIssue(primaryMode: String?, targetObject: String?, trigger: String?, sourceSymptom: String): String {
        if (primaryMode == null) return sourceSymptom.takeIf { it.length <= MAX_PHENOMENON_LENGTH } ?: LOW_INFORMATION
        val target = targetObject?.trim()?.takeIf(String::isNotEmpty)
        val operation = ClusterProblemOntology.operationClass(trigger)
        return when (primaryMode) {
            "GRADING_ERROR" -> when (target) {
                "错题本" -> "错题本中的作答批改结果错误"
                "同步学", "优学练习" -> "${target}题目批改结果错误"
                else -> "作答批改结果错误"
            }
            "RECOGNITION_ERROR" -> when (target) {
                "同步学", "优学练习" -> "${target}手写字符识别错误"
                else -> "手写字符识别错误"
            }
            "CONTENT_ERROR" -> when (target) {
                "错题本" -> "错题本中的答案或解析内容错误"
                "同步学", "优学练习" -> "${target}题目内容存在错误"
                else -> "题目内容存在错误"
            }
            "FREEZE" -> when (target) {
                "视频" -> "播放视频时出现卡顿"
                "课程" -> "使用课程时出现卡顿"
                "屏幕" -> "设备屏幕卡住"
                null -> "使用过程中出现卡顿"
                else -> "使用${target}时出现卡顿"
            }
            "NETWORK" -> "设备网络连接异常"
            "NO_AUDIO" -> when (target) {
                "视频" -> "播放视频时没有声音"
                null -> "使用过程中没有声音"
                else -> "使用${target}时没有声音"
            }
            "CRASH" -> when (operation) {
                "CLICK" -> target?.let { "点击${it}后应用闪退" } ?: "点击操作后应用闪退"
                "START" -> target?.let { "启动${it}时应用闪退" } ?: "应用启动时闪退"
                "OPEN" -> target?.let { "打开${it}时应用闪退" } ?: "打开应用时闪退"
                "ENTER" -> target?.let { "进入${it}时应用闪退" } ?: "进入页面时应用闪退"
                else -> target?.let { "使用${it}时应用闪退" } ?: "应用使用过程中闪退"
            }
            "NO_RESPONSE" -> if (operation == "SUBMIT") {
                target?.let { "提交${it}后页面持续转圈" } ?: "提交后页面持续转圈"
            } else target?.let { "使用${it}时页面无响应" } ?: "操作后页面无响应"
            "PLAYBACK" -> target?.let { "播放${it}时无法正常播放" } ?: "媒体内容无法正常播放"
            "NAVIGATION" -> target?.let { "无法进入${it}" } ?: "无法进入目标页面"
            "DOWNLOAD" -> target?.let { "下载${it}时失败" } ?: "内容下载失败"
            "UPLOAD" -> target?.let { "上传${it}时失败" } ?: "内容上传失败"
            "SUBMIT" -> target?.let { "提交${it}时无法完成提交" } ?: "内容提交失败"
            else -> sourceSymptom
        }
    }

    private fun clean(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
        ?.replace(Regex("^(?:【[^】]+】\\s*)+"), "")
        ?.replace(Regex("^(?:客户描述|用户反馈|问题描述|现象)[\\s:：-]*"), "")
        ?.replace(Regex("[；;，,]?\\s*记录编号\\s*[A-Za-z0-9_-]+[。.]?\\s*$"), "")
        ?.replace(Regex("\\s*\\{\\s*\"(?:blurLevel|isTabFocus)\".*$"), "")
        ?.replace(Regex("\\[[A-Z_]{2,32}]"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim(' ', '：', ':', '-', '·')
        ?.takeIf(String::isNotEmpty)

    private fun isConcrete(value: String, functionModule: String?, issueCategory: String?): Boolean {
        if (value.length < 4 || isPlaceholder(value)) return false
        if (LOW_INFORMATION_TEXT.any { it.matches(value) }) return false
        if (value.matches(Regex("^[a-z]{6,}$", RegexOption.IGNORE_CASE))) return false
        if (!PROBLEM_SIGNAL.containsMatchIn(value)) return false
        if (listOfNotNull(functionModule, issueCategory).any { value.equals(it.trim(), ignoreCase = true) }) return false
        if (value.contains('·') && value.split('·').all { it.trim().length <= 40 }) return false
        if (value.matches(Regex("(?i)^(功能模块|模块|问题分类|分类|issue|module)[\\s:：_-]*.+$"))) return false
        if (value.matches(Regex("^[A-Z0-9_.:/#-]{4,}$"))) return false
        return true
    }

    private fun knownChannelSentence(title: String?): String? = when (title) {
        "应用缺失" -> "应用商店中缺少用户需要的应用"
        "读书应用添加图书申请" -> "读书应用中缺少用户想阅读的图书"
        else -> null
    }

    private fun incompleteTargetSentence(targetObject: String?): String? = when (targetObject) {
        "麦克风" -> "使用麦克风时出现异常，但反馈未说明具体表现"
        "摄像头" -> "使用摄像头时出现异常，但反馈未说明具体表现"
        "视频" -> "播放视频时出现异常，但反馈未说明具体表现"
        "课程" -> "使用课程功能时出现异常，但反馈未说明具体表现"
        "助教" -> "使用AI助教时出现异常，但反馈未说明具体表现"
        "题库" -> "使用题库时出现异常，但反馈未说明具体表现"
        "错题本" -> "使用错题本时出现异常，但反馈未说明具体表现"
        "登录" -> "登录时出现异常，但反馈未说明具体表现"
        "支付" -> "支付时出现异常，但反馈未说明具体表现"
        else -> null
    }

    private fun normalizeShortProblemSentence(value: String?): String? = when (value?.trim(' ', '。', '.', '！', '!')) {
        "卡了", "卡住了", "很卡" -> "使用过程中出现卡顿"
        "打扰我" -> "使用过程中受到干扰"
        else -> value
    }

    /** 保留完整的问题子句；绝不能把过长的持久化值从句子中间截断。 */
    private fun boundedConcretePhenomenon(
        value: String?,
        functionModule: String?,
        issueCategory: String?
    ): String? {
        val normalized = normalizeShortProblemSentence(clean(value)) ?: return null
        if (normalized.length <= MAX_PHENOMENON_LENGTH) {
            return normalized.takeIf {
                isConcrete(it, functionModule, issueCategory) && !isGenericProblemText(it)
            }
        }
        return normalized.split(Regex("[。！？!?；;，,]+"))
            .asSequence()
            .mapNotNull(::clean)
            .firstOrNull { clause ->
                clause.length <= MAX_PHENOMENON_LENGTH &&
                    isConcrete(clause, functionModule, issueCategory) &&
                    !isGenericProblemText(clause)
            }
    }

    private fun isObviousNoise(value: String?): Boolean {
        val text = value?.trim() ?: return false
        if (text.isEmpty()) return false
        if (text in setOf("无", "一", "1", "10", "很好", "太好了", "反应快")) return true
        if (text.matches(Regex("^[\\p{P}\\p{S}\\d]+$"))) return true
        if (text.matches(Regex("(?i)^[a-z][a-z\\s%_-]{5,}$"))) return true
        if (text.matches(Regex("(?i)^[a-z0-9]{10,}$"))) return true
        if (Regex("(?i)(?:\\.com|\\.cn)").findAll(text).count() >= 3) return true
        if (Regex("^(.{1,4})\\1+$").matches(text)) return true
        val meaningful = text.count { it.isLetterOrDigit() }
        val symbols = text.length - meaningful
        return text.length >= 12 && symbols * 3 > text.length
    }
}
