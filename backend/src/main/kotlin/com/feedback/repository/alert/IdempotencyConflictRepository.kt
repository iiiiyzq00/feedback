package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository

@Repository
class IdempotencyConflictRepository(private val conflicts:IdempotencyConflictMapper,private val users:ConflictUserMapper){
    fun insert(row:IdempotencyConflictInsertPo){ensureAlertRepositoryState(conflicts.insert(row) in 0..1)}
    fun list(status:String?):List<Pair<IdempotencyConflictPo,String?>>{
        val rows=conflicts.find(status);val ids=rows.mapNotNull{it.resolvedBy}.distinct()
        val names=if(ids.isEmpty()) emptyMap() else users.findNames(ids).associate{it.id to it.displayName}
        return rows.map{it to it.resolvedBy?.let(names::get)}
    }
    fun resolve(id:Long,status:String,action:String,note:String,actor:Long)=conflicts.resolve(id,status,action,note,actor)==1
}
