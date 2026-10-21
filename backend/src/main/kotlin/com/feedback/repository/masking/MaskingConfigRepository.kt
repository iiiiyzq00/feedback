package com.feedback.repository.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.masking.MaskingConfigMapper
import com.feedback.model.po.masking.MaskingRuntimeConfigWritePo
import org.springframework.stereotype.Repository

@Repository
class MaskingConfigRepository(private val mapper:MaskingConfigMapper) {
    fun active(configKey:String)=mapper.findActiveByKey(configKey)
    fun draftRelease(version:String)=mapper.findDraftRelease(version)
    fun byType(configType:String)=mapper.findByType(configType)
    fun releaseForUpdate(version:String)=mapper.findReleaseForUpdate(version)
    fun byTypeAndKey(configType:String,configKey:String)=mapper.findByTypeAndKey(configType,configKey)
    fun nextVersionNumber()=mapper.countByType("MASKING_RELEASE")+1
    fun insertRelease(version:String,rulesJson:String,allowlist:String,reason:String,actor:Long) {
        if(mapper.insertRelease(version,rulesJson,allowlist,reason,actor)!=1) failed("MASKING_RELEASE_INSERT_FAILED")
    }
    fun publishRelease(id:Long,reason:String,actor:Long) {
        if(mapper.publishRelease(id,reason,actor)!=1) failed("MASKING_RELEASE_PUBLISH_FAILED")
    }
    fun replaceRuntime(rows:List<MaskingRuntimeConfigWritePo>) {
        if(rows.size!=3||rows.map{it.configKey}.distinct().size!=3) failed("MASKING_RUNTIME_CONFIG_INVALID")
        mapper.deactivateRuntimeKeys(rows.map{it.configKey})
        if(mapper.insertRuntimeConfigs(rows)!=rows.size) failed("MASKING_RUNTIME_CONFIG_INSERT_FAILED")
    }
    private fun failed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}
