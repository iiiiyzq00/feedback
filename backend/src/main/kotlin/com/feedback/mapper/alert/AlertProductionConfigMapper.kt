package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface AlertProductionConfigMapper{
 fun expireContracts():Int;fun expireProviders():Int;fun contracts():List<GovernanceContractPo>
 fun saveContract(@Param("code")code:String,@Param("schema")schema:String,@Param("owner")owner:String,@Param("source")source:String,@Param("fresh")fresh:Int,@Param("quality")quality:Double):Int
 fun validateContract(@Param("code")code:String,@Param("sha")sha:String,@Param("summary")summary:String,@Param("actor")actor:Long,@Param("expires")expires:LocalDateTime):Int
 fun approveContract(@Param("code")code:String,@Param("actor")actor:Long):Int;fun suspendContract(@Param("code")code:String):Int
 fun providers():List<GovernanceProviderPo>
 fun updateProvider(@Param("channel")channel:String,@Param("provider")provider:String,@Param("environment")environment:String,@Param("cap")cap:String,@Param("quota")quota:Long,@Param("sha")sha:String,@Param("ref")ref:String,@Param("actor")actor:Long,@Param("expires")expires:LocalDateTime):Int
 fun insertProvider(@Param("channel")channel:String,@Param("provider")provider:String,@Param("environment")environment:String,@Param("cap")cap:String,@Param("quota")quota:Long,@Param("sha")sha:String,@Param("ref")ref:String,@Param("actor")actor:Long,@Param("expires")expires:LocalDateTime):Int
 fun approveProvider(@Param("id")id:Long,@Param("actor")actor:Long):Int;fun suspendProvider(@Param("id")id:Long):Int
 fun requirements(@Param("code")code:String):List<GovernanceRequirementPo>;fun contractStates(@Param("codes")codes:List<String>):List<GovernanceContractStatePo>
 fun implementRule(@Param("code")code:String,@Param("sha")sha:String,@Param("actor")actor:Long):Int;fun catalog(@Param("code")code:String):GovernanceCatalogPo?
 fun readyProviders(@Param("channels")channels:List<String>):List<GovernanceProviderReadyPo>
 fun oncallUsers(@Param("team")team:String):List<GovernanceUserIdPo>;fun primaryUsers(@Param("team")team:String):List<GovernanceUserIdPo>
}

