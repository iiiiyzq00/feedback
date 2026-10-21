package com.feedback.repository.feedback.listprojection

import com.feedback.mapper.feedback.listprojection.FeedbackListApplicationDictionaryMapper
import com.feedback.model.po.feedback.listprojection.FeedbackListApplicationDictionaryPo
import org.springframework.stereotype.Repository

/** 投影构建使用的受界应用名称字典。 */
@Repository
class FeedbackListProjectionDictionaryRepository(
    private val mapper: FeedbackListApplicationDictionaryMapper
) {
    fun applicationNames(): List<String> = mapper.findNames(APPLICATION_DICTIONARY_SENTINEL)
        .also { if (it.size >= APPLICATION_DICTIONARY_SENTINEL) projectionReadUnavailable("FEEDBACK_APPLICATION_DICTIONARY_TOO_LARGE") }
        .map(FeedbackListApplicationDictionaryPo::applicationName)
        .filter(String::isNotBlank)

    private companion object { const val APPLICATION_DICTIONARY_SENTINEL = 501 }
}
