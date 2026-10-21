package com.feedback.model.po.feedback.listprojection

import com.fasterxml.jackson.annotation.JsonIgnore

sealed interface FeedbackListChannelFactsPo {
    @get:JsonIgnore
    val sourceId: Long
}
