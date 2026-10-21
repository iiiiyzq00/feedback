package com.feedback.model.po.problem.analysis

/** 单个簇的应用或地域维度计数；dimensionValue 的业务含义由调用的 Mapper 方法确定。 */
data class ProblemDimensionPo(var clusterId: Long = 0, var dimensionValue: String = "", var count: Long = 0)
