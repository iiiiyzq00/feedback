package com.feedback.repository.scheduler
import com.feedback.mapper.scheduler.MaintenanceLockMapper
import org.springframework.stereotype.Repository
@Repository class MaintenanceLockRepository(private val mapper:MaintenanceLockMapper){fun acquire(name:String)=mapper.acquire(name)==1;fun release(name:String)=mapper.release(name)}
