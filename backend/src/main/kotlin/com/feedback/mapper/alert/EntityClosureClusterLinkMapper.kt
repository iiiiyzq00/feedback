package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface EntityClosureClusterLinkMapper { fun sourceIds(@Param("ids")ids:List<Long>):List<ClosureClusterLinkPo> }

