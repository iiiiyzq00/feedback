package com.feedback.service.masking

import com.feedback.model.masking.MaskingAudit
import com.feedback.repository.MaskingAuditRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import com.feedback.service.alert.SecuritySignalService
import java.text.Normalizer

/** 只在本地运行的 PII 闸门。原始输入与命中值绝不写入审计日志。 */
@Service
class MaskingPipeline(
    private val auditRepository: MaskingAuditRepository,
    private val objectMapper: ObjectMapper,
    private val nerMasker: NerMasker,
    private val ruleRegistry: MaskingRuleRegistry? = null,
    private val securitySignals: SecuritySignalService? = null
) {
    fun activeVersion():String=ruleRegistry?.activeVersion()?:RULE_VERSION
    fun mask(text: String, evaluationKey: String = "single_text", recordAudit: Boolean = true): MaskingResult {
        var masked = normalize(text)
        val findings = mutableListOf<MaskingFinding>()

        // 确定性的高召回规则必须先于概率性 NER 执行。长标识符
        // 刻意排在手机号模式之前，这样身份证的子串就不会变成手机号命中。
        val enabledRules = ruleRegistry?.enabledNames()
        val jiraContextFixActive = ruleRegistry == null || activeVersion().startsWith("masking-v")
        val effectiveRules = if (jiraContextFixActive) RULES else RULES.map { rule -> when(rule.name) {
            "DEVICE_ID" -> rule.copy(regex = LEGACY_DEVICE_ID)
            "IP" -> rule.copy(regex = LEGACY_IP)
            else -> rule
        } }
        effectiveRules.filter { enabledRules == null || it.name in enabledRules }.forEach { rule ->
            masked = rule.regex.replace(masked) { match ->
                findings += MaskingFinding(rule.name, match.value)
                // Regex.replace(transform) 原样返回回调结果，不会展开 "$1"
                // 这类模板。因此显式保留 IP 的前两段。
                if (rule.name == "IP") "${match.groupValues[1]}.*.*" else rule.replacement
            }
        }

        if (enabledRules == null || "LANDLINE" in enabledRules) {
            masked = maskContextualLandlines(masked, findings)
        }

        masked = maskContextualNames(masked, findings)
        masked = SCHOOL.replace(masked) {
            findings += MaskingFinding("SCHOOL", it.value)
            "某学校"
        }
        masked = CLASS.replace(masked) {
            findings += MaskingFinding("CLASS", it.value)
            "某班级"
        }
        masked = ADDRESS_DETAIL.replace(masked) {
            findings += MaskingFinding("ADDRESS", it.value)
            "[ADDRESS_REMOVED]"
        }

        val nerResult = nerMasker.mask(masked)
        masked = nerResult.text
        findings += nerResult.findings

        val residual = RESIDUAL_DIGITS.containsMatchIn(masked.filterNot(Char::isWhitespace))
        if (residual) {
            masked = RESIDUAL_DIGITS.replace(masked) { match ->
                findings += MaskingFinding("LONG_NUMBER", match.value)
                "[LONG_NUMBER_REMOVED]"
            }
        }
        // 第一阶段没有人工隐私复核状态。确定性规则（含兜底的长数字
        // 规则）产出平台侧的安全副本。可选的 NER 可以增强脱敏，
        // 但它不可用时不能把数据扣在某个人手里。
        val status = "PASS"
        if (recordAudit && residual) securitySignals?.recordResidual(evaluationKey.take(100), findings.map { it.type }.distinct())
        if (recordAudit) auditRepository.save(MaskingAudit(
            auditType = "EVALUATION",
            maskingRuleVersion = activeVersion(),
            evaluationKey = evaluationKey.take(100),
            evalPassedSamples = if (status == "PASS") 1 else 0,
            evalTotalSamples = 1,
            evalErrorDetails = objectMapper.writeValueAsString(objectMapper.createObjectNode().apply {
                put("status", status)
                put("release_mode", "RULE_MASKED_AUTO_RELEASE")
                put("finding_count", findings.size)
                putPOJO("finding_types", findings.map { it.type }.distinct())
                put("residual_high_risk_digits", residual)
                put("ner_available", nerResult.available)
            })
        ))
        return MaskingResult(masked, status, findings.map { it.type }.distinct(), findings,
            residualHighRiskDigits = residual, nerAvailable = nerResult.available)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace("\u00A0", " ")

    private fun maskContextualNames(text: String, findings: MutableList<MaskingFinding>): String {
        var value = text
        CONTEXT_NAMES.forEach { pattern ->
            value = pattern.replace(value) {
                val entity = it.groupValues.getOrNull(2)?.takeIf(String::isNotBlank) ?: it.value
                findings += MaskingFinding("NAME", entity)
                it.groupValues[1] + "[NAME_REMOVED]" + it.groupValues.getOrElse(3) { "" }
            }
        }
        return value
    }

    private fun maskContextualLandlines(text: String, findings: MutableList<MaskingFinding>): String =
        CONTEXT_LANDLINE.replace(text) {
            val number = it.groupValues[3]
            findings += MaskingFinding("LANDLINE", number)
            it.groupValues[1] + it.groupValues[2] + "[LANDLINE_REMOVED]"
        }

    companion object {
        const val RULE_VERSION = "pii-v4-device-label"

        // 顺序关乎安全：凭据与长数字标识符必须排在手机号规则之前。
        private val RULES = listOf(
            MaskingRule("CREDENTIAL", Regex("(?i)(password|passwd|pwd|验证码|cookie|token|api[_-]?key|secret)\\s*[:=：]\\s*[^\\s,，;；]+"), "[CREDENTIAL_REMOVED]"),
            MaskingRule("ID_CARD", Regex("(?<![0-9])(?:[1-9][0-9]{5}(?:18|19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx])(?![0-9])"), "[ID_CARD_REMOVED]"),
            MaskingRule("PASSPORT", Regex("(?i)(?:护照|passport|港澳通行证|台胞证|证件号)\\s*[:=：]?\\s*[A-Z0-9]{6,18}"), "[DOCUMENT_REMOVED]"),
            MaskingRule("DEVICE_ID", Regex("(?i)(?:IMEI|IMSI|Android[_ ]?ID|OAID|Advertising[_ ]?ID|设备(?:序列号|SN)|序列号|SN码?|机器码|样机(?:编号)?)[*_】）》)\\]]*\\s*[:=：]?\\s*[A-Z0-9_-]{8,64}"), "[DEVICE_ID_REMOVED]"),
            MaskingRule("IMEI_IMSI", Regex("(?<![0-9])[0-9]{15}(?![0-9])"), "[DEVICE_ID_REMOVED]"),
            MaskingRule("STUDENT_EMPLOYEE_ID", Regex("(?i)(?:学号|工号|员工号|内部账号|家长账号)\\s*[:=：]?\\s*[A-Z0-9_-]{4,32}"), "[ACCOUNT_REMOVED]"),
            MaskingRule("COORDINATE", Regex("(?i)(?:经纬度|latitude|longitude|lat|lng)\\s*[:=：]?\\s*-?[0-9]{1,3}\\.[0-9]{4,}"), "[LOCATION_REMOVED]"),
            // 不能仅仅因为受治理的日期/时间串里有很多被连字符分隔的数字，
            // 就把它当成银行卡号。
            MaskingRule("BANK_CARD", Regex("(?<![0-9])(?<![0-9]{2}-)(?!20[0-9]{2}[-/.][0-9]{1,2}[-/.][0-9]{1,2}(?:[- T][0-9]{1,2})?)[0-9][0-9 -]{14,21}[0-9](?![0-9])"), "[BANK_CARD_REMOVED]"),
            MaskingRule("PHONE", Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9](?:[- ]?[0-9]){8}(?![0-9])"), "[PHONE]"),
            // 单独的 7/8 位数字与紧凑日期、业务标识符无法区分。这里要求
            // 必须带区号；纯本地号码只在出现明确的固话标签时
            // 才处理。
            MaskingRule("LANDLINE", Regex("(?<![0-9])0[0-9]{2,3}[- ]?[2-9][0-9]{6,7}(?![0-9])"), "[LANDLINE_REMOVED]"),
            MaskingRule("EMAIL", Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
            // Jira 导出的连字符时间戳看起来像 MAC 尾段，例如
            // 2025-06-24-18-42-30-01。不要在四位年份之后紧接着
            // 开始匹配 MAC；真正的冒号/连字符 MAC 值仍然能匹配上。
            MaskingRule("MAC", Regex("(?i)(?<!20\\d{2}-)(?<![0-9a-f])(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}(?![0-9a-f])"), "[MAC_REMOVED]"),
            // 形如 V3.3.1.4 的带点应用/系统版本号不是 IP 地址。
            MaskingRule("IP", Regex("(?<![0-9Vv])((?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9]))\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9])"), "$1.*.*"),
            MaskingRule("ACCOUNT", Regex("(?i)(?:账号|帐号|会员号|user(?:name)?|account)\\s*[:=：]?\\s*[A-Za-z0-9_-]{4,}"), "[ACCOUNT_REMOVED]")
        )
        private val LEGACY_DEVICE_ID = Regex("(?i)(?:IMEI|IMSI|Android[_ ]?ID|OAID|Advertising[_ ]?ID|设备(?:序列号|SN)|序列号|SN码?|机器码)[*_】）》)\\]]*\\s*[:=：]?\\s*[A-Z0-9_-]{8,64}")
        private val LEGACY_IP = Regex("(?<![0-9])((?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9]))\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9])")
        private val CONTEXT_NAMES = listOf(
            Regex("(叫|联系|找|我是|姓名[:：]?)([\\u4e00-\\u9fa5]{1,4})()"),
            Regex("(学生|学员|孩子|儿童)[:：]?([赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜戚谢邹" +
                "喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳唐罗薛雷贺倪汤滕殷毕郝邬安常乐于时傅皮卞齐康伍余元" +
                "顾孟黄穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅" +
                "盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯管卢莫房裘缪解应宗丁宣贲邓郁单杭洪包诸左石崔吉龚程嵇邢滑裴陆荣" +
                "翁荀羊甄曲封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘钭厉戎祖武符刘" +
                "景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦" +
                "雍郤璩桑桂濮牛寿通边扈燕冀浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国" +
                "文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯" +
                "盖益桓公][\\u4e00-\\u9fa5]{1,2})(?=反馈|表示|说|遇到|无法|不能|在|$)"),
            Regex("()([\\u4e00-\\u9fa5]{1,4})(妈妈|爸爸|老师|同学|家长|班主任)")
        )
        private val CONTEXT_LANDLINE = Regex("(电话|座机|固话)(\\s*[:：]?\\s*)([2-9][0-9]{6,7})(?![0-9])")
        private val SCHOOL = Regex("[\\u4e00-\\u9fa5]{2,20}(?:小学|中学|学校|学院|幼儿园)")
        private val CLASS = Regex("(?:[一二三四五六七八九0-9]{1,2}年级)?[一二三四五六七八九0-9]{1,2}班")
        // 要求「街道后缀 + 具体门牌号」。只看后缀的宽泛匹配
        // 会把“记录编号”“教室”这类普通词误判。
        private val ADDRESS_DETAIL = Regex("[\\u4e00-\\u9fa5]{2,30}(?:路|街|巷|弄)[0-9一二三四五六七八九十-]{1,8}(?:号|栋|单元|室)")
        // 上面的确定性规则已经覆盖了格式化的手机号/卡号/身份证号。
        // 这条残余兜底只针对未知的长标识符；
        // 一旦允许分隔符，2026-08-01 这类日期就会被误判。
        private val RESIDUAL_DIGITS = Regex("(?<![0-9])[0-9]{10,}(?![0-9])")
    }
}

data class MaskingResult(
    val maskedText: String,
    val maskingStatus: String,
    val findingTypes: List<String>,
    val findings: List<MaskingFinding> = emptyList(),
    val residualHighRiskDigits: Boolean = false,
    val nerAvailable: Boolean = true
)

data class MaskingFinding(val type: String, val originalValue: String)
private data class MaskingRule(val name: String, val regex: Regex, val replacement: String)
