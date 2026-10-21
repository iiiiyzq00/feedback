package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.security.WebPrincipal
import com.feedback.service.cluster.ClusterReclassificationRollbackRequest
import com.feedback.service.cluster.ClusterReclassificationNameQualityRepairRequest
import com.feedback.service.cluster.ClusterReclassificationMemberRepairRequest
import com.feedback.service.cluster.ClusterReclassificationService
import com.feedback.service.cluster.ClusterReclassificationStartRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/cluster-reclassification")
class ClusterReclassificationController(
    private val service: ClusterReclassificationService
) {
    @PostMapping("", "/start")
    fun start(@RequestBody request: ClusterReclassificationStartRequest, auth: Authentication) =
        ApiResponse.success(service.start(UUID.fromString(request.requestId), request.reason, actor(auth)))

    @GetMapping("/latest")
    fun latest() = ApiResponse.success(service.latest())

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long) = ApiResponse.success(service.findById(id))

    @PostMapping("/{id}/rollback")
    fun rollback(
        @PathVariable id: Long,
        @RequestBody request: ClusterReclassificationRollbackRequest,
        auth: Authentication
    ) = ApiResponse.success(service.rollback(id, request.reason, actor(auth)))

    @PostMapping("/{id}/repair-name-quality")
    fun repairNameQuality(
        @PathVariable id: Long,
        @RequestBody request: ClusterReclassificationNameQualityRepairRequest,
        auth: Authentication
    ) = ApiResponse.success(service.repairNameQualityFailure(id, request.reason, actor(auth)))

    @PostMapping("/{id}/repair-members")
    fun repairMembers(
        @PathVariable id: Long,
        @RequestBody request: ClusterReclassificationMemberRepairRequest,
        auth: Authentication
    ) = ApiResponse.success(service.repairMembers(id, request.sourceIds, request.reason, actor(auth)))

    private fun actor(auth: Authentication): Long = (auth.principal as WebPrincipal).userId
}
