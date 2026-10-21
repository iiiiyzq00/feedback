package com.feedback.model.po.problem.analysis

/** 仅基于不可逆指纹聚合；没有 PERSON 身份事实时 userCount 保持 null，不能解释为零用户。 */
data class ProblemIdentityCountPo(var clusterId: Long = 0, var userCount: Long? = null, var deviceCount: Long = 0)
