package com.feedback.sdk.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechInputTextTest {
    @Test fun `inserts recognized text at cursor without overwriting text`() {
        assertEquals("已有 描述内容" to 5, SpeechInputText.insert("已有内容", 2, "描述"))
    }

    @Test fun `invalid cursor falls back to start boundary`() {
        assertEquals("语音原文" to 2, SpeechInputText.insert("原文", -1, "语音"))
    }
}
