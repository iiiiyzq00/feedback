package com.feedback.model.cluster

import java.security.MessageDigest
import java.util.Locale

const val CLUSTER_NAME_KEY_VERSION = "cluster-name-key-v1"

/** 规范化簇名用于稳定身份计算，不保留首尾空白或大小写差异。 */
fun normalizeClusterNameIdentity(value: String): String = value.trim()
    .replace(Regex("\\s+"), " ")
    .lowercase(Locale.ROOT)

/** 使用固定版本规范化规则生成簇名 SHA-256 稳定键。 */
fun stableClusterNameKey(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizeClusterNameIdentity(value).toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
