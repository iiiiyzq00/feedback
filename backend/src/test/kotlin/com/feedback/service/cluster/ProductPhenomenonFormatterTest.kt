package com.feedback.service.cluster

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProductPhenomenonFormatterTest {
    @Test
    fun `cluster name fallback never creates an operational pending placeholder`() {
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.choose(null, "普通描述", null, null)
        )
    }

    @Test
    fun `card lag and interference remain concrete product phenomena`() {
        assertEquals(
            "使用过程中出现卡顿",
            ProductPhenomenonFormatter.choose(null, "使用过程中出现卡顿", null, null)
        )
        assertEquals(
            "使用过程中受到干扰",
            ProductPhenomenonFormatter.choose(null, "使用过程中受到干扰", null, null)
        )
        assertEquals(
            "使用课程时出现卡顿",
            ProductPhenomenonFormatter.forPrimaryIssue("FREEZE", "课程", "上课", "上课过程中出现卡顿")
        )
    }
    @Test
    fun `every primary problem name round trips to its selected failure mode`() {
        val modes = listOf(
            "GRADING_ERROR", "RECOGNITION_ERROR", "CONTENT_ERROR", "FREEZE", "NETWORK", "NO_AUDIO",
            "CRASH", "NO_RESPONSE", "PLAYBACK", "NAVIGATION", "DOWNLOAD", "UPLOAD", "SUBMIT"
        )
        modes.forEach { mode ->
            val name = ProductPhenomenonFormatter.forPrimaryIssue(mode, "视频", "点击播放视频", "原始反馈")
            assertEquals(true, mode in ClusterProblemOntology.failureModes(name), "$mode -> $name")
        }
    }

    @Test
    fun `uses concrete product symptom instead of module and category fallback`() {
        assertEquals(
            "提交作业后页面一直转圈，无法完成提交",
            ProductPhenomenonFormatter.choose(
                "作业模块 · 功能异常",
                "用户反馈：提交作业后页面一直转圈，无法完成提交",
                "作业模块",
                "功能异常"
            )
        )
    }

    @Test
    fun `keeps a concrete canonical symptom`() {
        assertEquals(
            "打开课程视频时黑屏且无法播放",
            ProductPhenomenonFormatter.choose("打开课程视频时黑屏且无法播放", "视频模块", "视频模块", "播放异常")
        )
    }

    @Test
    fun `does not invent a problem when only taxonomy metadata exists`() {
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.choose("视频模块 · 播放异常", "模块：视频模块", "视频模块", "播放异常")
        )
    }

    @Test
    fun `recognizes placeholder combinations as non concrete`() {
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.choose("问题现象待归纳/其他/测试", null)
        )
    }

    @Test
    fun `generic channel label falls through to masked description`() {
        assertEquals(
            "进入同步学后数学题答案与解析不一致",
            ProductPhenomenonFormatter.fromSource(
                "同步学(其他错误)",
                "进入同步学后数学题答案与解析不一致",
                "同步学(其他错误)",
                null,
                null,
                null
            )
        )
    }

    @Test
    fun `generic problem title cannot hide a concrete description`() {
        assertEquals(
            "打开课程后视频黑屏且无法播放",
            ProductPhenomenonFormatter.fromSource(
                "页面异常", "打开课程后视频黑屏且无法播放", null, null, null, null
            )
        )
    }

    @Test
    fun `generic application label keeps concrete issue and removes telemetry tail`() {
        assertEquals(
            "一直在这里卡住",
            ProductPhenomenonFormatter.fromSource(
                "应用程序", "一直在这里卡住{\"blurLevel\":9.18,\"isTabFocus\":false}", null, null, null, null
            )
        )
    }

    @Test
    fun `generic application label rejects sentiment and gibberish as a cluster name`() {
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.fromSource("应用程序", "非常不好。", null, null, null, null)
        )
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.fromSource("应用程序", "fxhibudyg", null, null, null, null)
        )
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.fromSource("识别有错误", "全部都是对的", null, null, null, null)
        )
    }

    @Test
    fun `creates one sentence for meaningful short feedback instead of field labels`() {
        assertEquals(
            "使用麦克风时出现异常，但反馈未说明具体表现",
            ProductPhenomenonFormatter.fromSource("其他", "麦克风", null, null, null, null, "麦克风")
        )
        assertEquals(
            "播放视频时出现异常，但反馈未说明具体表现",
            ProductPhenomenonFormatter.fromSource("其他", "视频", null, null, null, null, "视频")
        )
        assertEquals(
            "使用过程中出现卡顿",
            ProductPhenomenonFormatter.fromSource("其他", "卡了", null, null, null, null)
        )
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.choose(ProductPhenomenonFormatter.LOW_INFORMATION, null)
        )
    }

    @Test
    fun `obvious garbage overrides an otherwise meaningful channel title`() {
        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.fromSource("应用缺失", "155412576ffhhcgg", null, null, null, null)
        )
        assertEquals(
            "应用商店中缺少用户需要的应用",
            ProductPhenomenonFormatter.fromSource("应用缺失", "宝宝巴士数学", null, null, null, null)
        )
    }

    @Test
    fun `keeps concrete quality symptoms and feature requests without error words`() {
        assertEquals("屏幕显示偏色", ProductPhenomenonFormatter.choose("屏幕显示偏色", null))
        assertEquals("麦克风声音太小", ProductPhenomenonFormatter.choose("麦克风声音太小", null))
        assertEquals("希望支持分屏", ProductPhenomenonFormatter.choose("希望支持分屏", null))
    }

    @Test
    fun `rejects overlong names instead of cutting an incomplete sentence`() {
        val overlong = "提交作业后页面持续转圈，导致无法完成提交，" + "仍然无法恢复".repeat(40)
        assertEquals(ProductPhenomenonFormatter.LOW_INFORMATION, ProductPhenomenonFormatter.choose(overlong, null))
    }

    @Test
    fun `extracts first complete concrete problem clause from overlong feedback`() {
        val overlong = "提交作业后页面持续转圈，无法完成提交。" + "补充背景信息".repeat(100)

        assertEquals(
            "提交作业后页面持续转圈",
            ProductPhenomenonFormatter.fromSource("其他", overlong, null, null, null, null)
        )
    }

    @Test
    fun `overlong run-on feedback never becomes an overlong cluster phenomenon`() {
        val overlong = "希望增加一个没有明确边界的功能".repeat(50)

        assertEquals(
            ProductPhenomenonFormatter.LOW_INFORMATION,
            ProductPhenomenonFormatter.fromSource("其他", overlong, null, null, null, null)
        )
    }

    @Test
    fun `safely evolves a submission name and rejects a name widened across failure modes`() {
        assertEquals(
            "提交作业后页面持续转圈，导致作业无法完成提交",
            ProductPhenomenonFormatter.evolve("提交作业后页面一直转圈", "点击提交后无法完成提交")
        )
        assertEquals(
            null,
            ProductPhenomenonFormatter.evolve("课程播放失败", "课程播放失败且下载失败")
        )
    }
}
