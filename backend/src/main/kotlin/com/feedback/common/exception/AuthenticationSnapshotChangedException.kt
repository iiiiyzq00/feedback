package com.feedback.common.exception

import org.springframework.security.core.AuthenticationException

/** 验密期间账号或授权版本发生变化；编排层可重新读取最新快照并有限重试。 */
class AuthenticationSnapshotChangedException : AuthenticationException("AUTHENTICATION_SNAPSHOT_CHANGED")
