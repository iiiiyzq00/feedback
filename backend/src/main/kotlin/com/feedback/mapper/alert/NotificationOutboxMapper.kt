package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * 通知发件箱的读写。
 *
 * 唯一一条**只读**语句是 [findClaimed]：它服务的是 MQ 消费轨——中继只把行号发到主题上，
 * 正文留在库里，于是消费侧必须能按行号把那条已认领的记录读回来。
 * 轮询轨用不到它（认领那一刻就把整行拿在手里了）。
 */
@Mapper interface NotificationOutboxMapper{fun claim():NotificationOutboxPo?;fun findClaimed(@Param("id")id:Long):NotificationOutboxPo?;fun markSent(@Param("id")id:Long):Int;fun delivered(@Param("row")row:NotificationCompletionPo):Int;fun deadLetter(@Param("row")row:NotificationCompletionPo):Int;fun failed(@Param("row")row:NotificationCompletionPo):Int}

