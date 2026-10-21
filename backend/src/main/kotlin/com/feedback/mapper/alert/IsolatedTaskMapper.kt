package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface IsolatedTaskMapper{
 fun insertTest(@Param("row")row:IsolatedTestInsertPo):Int
 fun updateResult(@Param("id")id:Long,@Param("result")result:String):Int
 fun insertDrill(@Param("row")row:IsolatedDrillInsertPo):Int
 fun drillPage(@Param("before")before:Long?,@Param("limit")limit:Int):List<IsolatedDrillPo>
 fun parents(@Param("ids")ids:List<Long>):List<IsolatedParentPo>
 fun lockDrill(@Param("displayId")displayId:Long):IsolatedLockedDrillPo?
 fun transition(@Param("id")id:Long,@Param("target")target:String,@Param("reason")reason:String,@Param("actor")actor:Long):Int
}

