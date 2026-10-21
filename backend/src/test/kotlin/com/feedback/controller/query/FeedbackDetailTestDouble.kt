package com.feedback.controller.query

import com.feedback.service.query.detail.FeedbackDetailQueryUseCase
import com.feedback.service.query.detail.FeedbackExternalLinkQueryUseCase
import com.feedback.service.query.detail.FeedbackModificationQueryUseCase
import com.feedback.service.query.detail.FeedbackTimelineQueryUseCase

/** 控制器契约测试共用替身类型；生产代码不存在聚合 Facade。 */
interface FeedbackDetailTestDouble : FeedbackDetailQueryUseCase, FeedbackModificationQueryUseCase,
    FeedbackTimelineQueryUseCase, FeedbackExternalLinkQueryUseCase
