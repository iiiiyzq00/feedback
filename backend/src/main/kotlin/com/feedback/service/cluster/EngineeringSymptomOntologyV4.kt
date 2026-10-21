package com.feedback.service.cluster

/** 面向工程师的封闭能力分类。标签都是安全常量；原始来源标签绝不展示。 */
enum class EngineeringCapabilityDomain(val label: String) {
    COURSE_LEARNING("课程与学习内容"),
    QUESTION_SUBMISSION("题目作答与提交"),
    RECOGNITION_INPUT("识别与输入"),
    GRADING_EXPLANATION("批改、答案与讲解"),
    MEDIA("音视频与朗读"),
    APP_LIFECYCLE("应用获取、安装与升级"),
    ACCOUNT_AUTH("账号、登录与权限"),
    NETWORK_SERVICE("网络与服务连接"),
    DATA_SYNC("数据保存、同步与恢复"),
    SEARCH_DISCOVERY("搜索、推荐与内容发现"),
    NAVIGATION_INTERACTION("页面导航与交互"),
    PERFORMANCE_STABILITY("性能与稳定性"),
    HARDWARE_DEVICE("设备与硬件"),
    SYSTEM_PLATFORM("系统平台能力"),
    CONTENT_QUALITY("内容质量与资源配置"),
    PARENTAL_CONTROL("家长管理与使用控制"),
    REQUEST_CONSULTATION("需求建议与使用咨询"),
    UNCLASSIFIED("待建模能力")
}

enum class EngineeringFaultFamily(val label: String) {
    UNAVAILABLE("不可用或无法进入"),
    LOADING("加载失败或缓慢"),
    NO_RESPONSE("无响应"),
    INCORRECT_RESULT("结果错误"),
    MISSING_CONTENT("内容缺失"),
    MISMATCH("内容不匹配"),
    STATE_STALE("状态未更新"),
    DATA_LOSS("数据丢失"),
    CONNECTIVITY("网络超时或连接中断"),
    PERMISSION("权限或鉴权失败"),
    TRANSFER_INSTALL("下载、上传或安装失败"),
    MEDIA_IO("播放、录音或声音异常"),
    RECOGNITION("识别失败或不准确"),
    STABILITY("卡顿、卡死或闪退"),
    DISPLAY_INTERACTION("显示或交互异常"),
    HARDWARE_FAILURE("硬件失效"),
    REQUEST("需求建议"),
    CONSULTATION("使用咨询"),
    UNKNOWN("待建模故障")
}

/** 确定性的封闭词表增强；不做任何 I/O，也绝不把 QUALITY 硬件当成应用。 */
class EngineeringSymptomOntologyV4 {
    fun enrich(input: ClusterClassificationInput, base: AtomicProblemProfile): AtomicProblemProfile {
        if (base.status in setOf(
                ProblemClassificationStatus.CONTENT_REVIEW_NEEDED,
                ProblemClassificationStatus.CONTENT_INELIGIBLE,
                ProblemClassificationStatus.NOISE,
                ProblemClassificationStatus.INSUFFICIENT_INFORMATION,
                ProblemClassificationStatus.LEGACY_INFORMATION_LOSS
            )) return base

        val text = listOfNotNull(input.title, input.description).joinToString("。")
        val domain = domain(input, text)
        val family = family(base.failureMode, text, domain)
        val pendingName = when (base.applicationScope) {
            ApplicationClusteringScope.RESOLVED -> base.applicationName
            ApplicationClusteringScope.PENDING_UNRESOLVED -> "${domain.label}待确认"
            ApplicationClusteringScope.PENDING_AMBIGUOUS -> "${domain.label}有歧义（待确认）"
        }
        if (base.status == ProblemClassificationStatus.ACTIONABLE) {
            val identity = listOf(
                "symptom-fingerprint-v4", partition(base, domain), domain.name, family.name,
                base.target.orEmpty(), base.action.orEmpty(), base.failureMode.orEmpty(),
                base.errorSignature.orEmpty(), normalizeClusterSimilarityText(base.canonicalSymptom.orEmpty())
            ).joinToString("|")
            return base.copy(
                applicationName = pendingName,
                capabilityDomain = domain,
                faultFamily = family,
                identityKey = sha256(identity)
            )
        }
        if (base.status == ProblemClassificationStatus.NOVEL_SYMPTOM &&
            domain != EngineeringCapabilityDomain.UNCLASSIFIED && family != EngineeringFaultFamily.UNKNOWN) {
            val symptom = genericSymptom(domain, family)
            val failureMode = "V4_${family.name}"
            val identity = listOf(
                "symptom-fingerprint-v4", partition(base, domain), domain.name,
                failureMode, symptom
            ).joinToString("|")
            return base.copy(
                applicationName = pendingName,
                status = ProblemClassificationStatus.ACTIONABLE,
                primaryClause = text.take(200),
                failureMode = failureMode,
                canonicalSymptom = symptom,
                identityKey = sha256(identity),
                reason = "明确异常线索已由受控能力域与故障族覆盖",
                evidenceSource = ProblemEvidenceSource.DIRECT_TEXT,
                capabilityDomain = domain,
                faultFamily = family
            )
        }
        return base.copy(applicationName = pendingName, capabilityDomain = domain, faultFamily = family)
    }

    private fun partition(base: AtomicProblemProfile, domain: EngineeringCapabilityDomain) =
        when (base.applicationScope) {
            ApplicationClusteringScope.RESOLVED -> "resolved:${base.applicationKey.orEmpty()}"
            ApplicationClusteringScope.PENDING_UNRESOLVED -> "pending:unresolved:${domain.name}"
            ApplicationClusteringScope.PENDING_AMBIGUOUS -> "pending:ambiguous:${domain.name}"
        }

    private fun domain(input: ClusterClassificationInput, text: String): EngineeringCapabilityDomain {
        val structured = listOfNotNull(input.functionModule, input.issueCategory).joinToString(" ")
        if (input.sourceChannel == "QUALITY" && HARDWARE.containsMatchIn(structured)) {
            return EngineeringCapabilityDomain.HARDWARE_DEVICE
        }
        return DOMAINS.firstOrNull { it.second.containsMatchIn(text) }?.first
            ?: when {
                input.sourceChannel == "QUALITY" && SYSTEM.containsMatchIn(structured) ->
                    EngineeringCapabilityDomain.SYSTEM_PLATFORM
                else -> EngineeringCapabilityDomain.UNCLASSIFIED
            }
    }

    private fun family(
        failureMode: String?,
        text: String,
        domain: EngineeringCapabilityDomain
    ): EngineeringFaultFamily {
        FAILURE_MODE_FAMILIES[failureMode]?.let { return it }
        if (domain == EngineeringCapabilityDomain.HARDWARE_DEVICE && FAULT_CUE.containsMatchIn(text)) {
            return EngineeringFaultFamily.HARDWARE_FAILURE
        }
        return FAMILIES.firstOrNull { it.second.containsMatchIn(text) }?.first
            ?: EngineeringFaultFamily.UNKNOWN
    }

    private fun genericSymptom(domain: EngineeringCapabilityDomain, family: EngineeringFaultFamily): String = when {
        domain == EngineeringCapabilityDomain.NETWORK_SERVICE && family == EngineeringFaultFamily.CONNECTIVITY ->
            "设备网络连接异常"
        else -> "${domain.label}${family.label}"
    }

    companion object {
        private val HARDWARE = Regex("硬件|主板|屏幕|摄像头|麦克风|扬声器|电池|触控|按键|充电|设备")
        private val SYSTEM = Regex("系统|桌面|固件|权限|存储|网络|蓝牙|wifi", RegexOption.IGNORE_CASE)
        private val FAULT_CUE = Regex("错误|故障|失败|异常|无法|不能|不了|损坏|失效|无响应|卡顿|闪退")
        private val DOMAINS = listOf(
            EngineeringCapabilityDomain.PARENTAL_CONTROL to Regex("家长|管控|使用时长|禁用|健康使用"),
            EngineeringCapabilityDomain.APP_LIFECYCLE to Regex("应用商店|应用目录|下载|安装|卸载|升级|更新|应用缺失"),
            EngineeringCapabilityDomain.ACCOUNT_AUTH to Regex("账号|账户|登录|注册|验证码|权限|鉴权|密码"),
            EngineeringCapabilityDomain.DATA_SYNC to Regex("保存|同步|恢复|记录|进度|数据丢失|云端"),
            EngineeringCapabilityDomain.SEARCH_DISCOVERY to Regex("搜索|推荐|找不到|目录|资源发现"),
            EngineeringCapabilityDomain.GRADING_EXPLANATION to Regex("批改|判错|答案|解析|讲解|答疑|算式|方程"),
            EngineeringCapabilityDomain.QUESTION_SUBMISSION to Regex("题目|作业|作答|提交|练习|错题本"),
            EngineeringCapabilityDomain.RECOGNITION_INPUT to Regex("识别|手写|书写|输入|扫描|拍题|摄像头"),
            EngineeringCapabilityDomain.MEDIA to Regex("视频|音频|播放|朗读|录音|声音|麦克风|绘本"),
            EngineeringCapabilityDomain.COURSE_LEARNING to Regex("课程|章节|上课|课堂|学习内容|教材"),
            EngineeringCapabilityDomain.NETWORK_SERVICE to Regex("网络|联网|断网|连接|超时|wifi|dns", RegexOption.IGNORE_CASE),
            EngineeringCapabilityDomain.HARDWARE_DEVICE to Regex("主板|屏幕|摄像头|麦克风|扬声器|电池|触控|按键|充电|开机|设备"),
            EngineeringCapabilityDomain.SYSTEM_PLATFORM to Regex("系统|桌面|固件|存储空间|蓝牙"),
            EngineeringCapabilityDomain.PERFORMANCE_STABILITY to Regex("卡顿|卡死|闪退|崩溃|慢|无响应|转圈"),
            EngineeringCapabilityDomain.NAVIGATION_INTERACTION to Regex("页面|按钮|点击|进入|退出|跳转|显示"),
            EngineeringCapabilityDomain.CONTENT_QUALITY to Regex("内容|资源|错别字|题库|缺失|不匹配|不健康"),
            EngineeringCapabilityDomain.REQUEST_CONSULTATION to Regex("建议|希望|需求|怎么|如何|请问|能否|可不可以")
        )
        private val FAILURE_MODE_FAMILIES = mapOf(
            "NETWORK" to EngineeringFaultFamily.CONNECTIVITY,
            "CRASH" to EngineeringFaultFamily.STABILITY,
            "FREEZE" to EngineeringFaultFamily.STABILITY,
            "NO_RESPONSE" to EngineeringFaultFamily.NO_RESPONSE,
            "LOADING_SLOW" to EngineeringFaultFamily.LOADING,
            "NAVIGATION" to EngineeringFaultFamily.UNAVAILABLE,
            "DOWNLOAD" to EngineeringFaultFamily.TRANSFER_INSTALL,
            "UPLOAD" to EngineeringFaultFamily.TRANSFER_INSTALL,
            "POST_DOWNLOAD_USAGE_FAILURE" to EngineeringFaultFamily.TRANSFER_INSTALL,
            "PLAYBACK" to EngineeringFaultFamily.MEDIA_IO,
            "NO_AUDIO" to EngineeringFaultFamily.MEDIA_IO,
            "MEDIA_ERROR" to EngineeringFaultFamily.MEDIA_IO,
            "BOOK_RECOGNITION_FAILURE" to EngineeringFaultFamily.RECOGNITION,
            "RECOGNITION_ERROR" to EngineeringFaultFamily.RECOGNITION,
            "QUESTION_RECOGNITION_ERROR" to EngineeringFaultFamily.RECOGNITION,
            "CONTENT_MISSING" to EngineeringFaultFamily.MISSING_CONTENT,
            "APP_MISSING" to EngineeringFaultFamily.MISSING_CONTENT,
            "QUESTION_MISMATCH" to EngineeringFaultFamily.MISMATCH,
            "DISPLAY_ERROR" to EngineeringFaultFamily.DISPLAY_INTERACTION,
            "INTERACTION_FAILURE" to EngineeringFaultFamily.DISPLAY_INTERACTION,
            "UI_CONTROL_FAILURE" to EngineeringFaultFamily.DISPLAY_INTERACTION,
            "PERMISSION_ENTRY_UNCLEAR" to EngineeringFaultFamily.PERMISSION,
            "GRADING_ERROR" to EngineeringFaultFamily.INCORRECT_RESULT,
            "ANSWER_CONTENT_ERROR" to EngineeringFaultFamily.INCORRECT_RESULT,
            "CONTENT_ERROR" to EngineeringFaultFamily.INCORRECT_RESULT,
            "SUBMIT" to EngineeringFaultFamily.UNAVAILABLE
        )
        private val FAMILIES = listOf(
            EngineeringFaultFamily.DATA_LOSS to Regex("丢失|清空|消失|没有保存"),
            EngineeringFaultFamily.CONNECTIVITY to Regex("超时|断网|无法联网|连接失败|网络异常|服务不可达"),
            EngineeringFaultFamily.PERMISSION to Regex("无权限|权限拒绝|鉴权失败|登录失败"),
            EngineeringFaultFamily.TRANSFER_INSTALL to Regex("下载|上传|安装|升级|更新"),
            EngineeringFaultFamily.MEDIA_IO to Regex("播放|录音|无声|没有声音|麦克风"),
            EngineeringFaultFamily.RECOGNITION to Regex("识别|认错|扫错"),
            EngineeringFaultFamily.STABILITY to Regex("卡顿|卡死|闪退|崩溃"),
            EngineeringFaultFamily.NO_RESPONSE to Regex("无响应|没反应|转圈"),
            EngineeringFaultFamily.LOADING to Regex("加载失败|加载不了|加载慢|一直加载"),
            EngineeringFaultFamily.MISSING_CONTENT to Regex("缺失|少了|缺少|找不到|没有.*内容"),
            EngineeringFaultFamily.MISMATCH to Regex("不匹配|不对应|不是这个|内容错位"),
            EngineeringFaultFamily.STATE_STALE to Regex("未更新|不同步|状态不变"),
            EngineeringFaultFamily.INCORRECT_RESULT to Regex("结果错误|答案错误|批改错误|识别错误|不准确|不对"),
            EngineeringFaultFamily.DISPLAY_INTERACTION to Regex("显示异常|显示错误|按钮无效|点击无效|无法操作"),
            EngineeringFaultFamily.UNAVAILABLE to Regex("无法|不能|不了|失败|不可用|打不开|进不去")
        )
    }
}
