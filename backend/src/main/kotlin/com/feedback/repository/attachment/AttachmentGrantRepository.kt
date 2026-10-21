package com.feedback.repository.attachment

import com.feedback.model.attachment.*

import com.feedback.application.port.feedback.attachment.AttachmentGrantStorePort

/**
 * 一次性附件授权仓储。
 *
 * 只保存不可逆令牌指纹；锁定与消费必须由上层同一个短事务包围，避免并发重复消费。
 */
interface AttachmentGrantRepository : AttachmentGrantStorePort
