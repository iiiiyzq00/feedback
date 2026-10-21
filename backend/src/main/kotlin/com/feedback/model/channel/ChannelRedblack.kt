package com.feedback.model.channel

import java.time.LocalDateTime

/** 红黑榜渠道反馈事实；敏感主体字段只保存受控脱敏值或不可逆摘要。 */
data class ChannelRedblack(
    val sourceId: Long,
    val originalId: Long? = null,
    val originalIdRaw: String? = null,
    val uidRaw: String? = null,
    val uidHash: ByteArray? = null,
    val headline: String? = null,
    val contentText: String? = null,
    val author: String? = null,
    val url: String? = null,
    val sourcePlatform: String? = null,
    val pubTypeName: String? = null,
    val sectionRaw: String? = null,
    val folderName: String? = null,
    val originMessage: String? = null,
    val keyword: String? = null,
    val emotionTypeRaw: String? = null,
    val evaluationObject: String? = null,
    val evaluationDimension: String? = null,
    val impactLevelRaw: String? = null,
    val dataPeriod: String? = null,
    val sourceDepartmentMasked: String? = null,
    val sourceRoleMasked: String? = null,
    val pushTime: LocalDateTime? = null,
    val pubTime: LocalDateTime? = null,
    val createTime: LocalDateTime? = null,
    val updateTime: LocalDateTime? = null,
    val isDeletedSrc: Boolean = false,
    val coverImageUrl: String? = null,
    val imageUrls: String? = null,
    val videoUrls: String? = null
)
