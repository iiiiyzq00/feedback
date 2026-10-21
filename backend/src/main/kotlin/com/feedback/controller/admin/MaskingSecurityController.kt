package com.feedback.controller.admin

import com.feedback.common.ApiResponse
import com.feedback.service.masking.ContactDecryptRequest
import com.feedback.service.masking.MaskingRetryRequest
import com.feedback.service.masking.RawInspectionRequest
import com.feedback.service.masking.MaskingSecurityService
import com.feedback.service.masking.SdkExceptionCloseRequest
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/masking-security")
class MaskingSecurityController(private val service: MaskingSecurityService) {
    @GetMapping("/quarantine")
    fun quarantine(@RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "20") size: Int,
                   @RequestParam(defaultValue="ACTIVE") view:String) =
        ApiResponse.success(service.quarantine(page, size,view))

    @PostMapping("/retry")
    fun retry(@RequestBody input: MaskingRetryRequest) = ApiResponse.success(service.retry(input))

    @PostMapping("/close")
    fun close(@RequestBody input:SdkExceptionCloseRequest)=ApiResponse.success(service.closeSdkExceptions(input))

    @PostMapping("/contacts/{sourceId}/decrypt")
    fun decrypt(@PathVariable sourceId: Long, @RequestBody input: ContactDecryptRequest, request: HttpServletRequest) =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .body(ApiResponse.success(service.decrypt(sourceId, input, request.remoteAddr)))

    @PostMapping("/raw-inspection")
    fun inspectRaw(@RequestBody input: RawInspectionRequest, request: HttpServletRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(ApiResponse.success(service.inspectRaw(input, request.remoteAddr)))

}
