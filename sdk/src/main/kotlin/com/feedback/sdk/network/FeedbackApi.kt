package com.feedback.sdk.network

import com.feedback.sdk.network.models.ApiResponseWrapper
import com.feedback.sdk.network.models.AttachmentAck
import com.feedback.sdk.network.models.FeedbackAck
import com.feedback.sdk.network.models.FeedbackRequest
import com.feedback.sdk.network.models.SDKRemoteConfig
import com.feedback.sdk.network.models.TelemetryRequest
import com.feedback.sdk.network.models.FeedbackProgress
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

/**
 * SDK API — 所有接口返回 ApiResponseWrapper<T> 与后端一致
 */
interface FeedbackApi {

    @POST("/api/v1/sdk/feedback")
    suspend fun submitFeedback(
        @Body request: FeedbackRequest
    ): Response<ApiResponseWrapper<FeedbackAck>>

    @GET("/api/v1/sdk/feedback/{receipt_id}/status")
    suspend fun getFeedbackProgress(@Path("receipt_id") receiptId:String):Response<ApiResponseWrapper<FeedbackProgress>>

    @Multipart
    @POST("/api/v1/sdk/ingest/{receipt_id}/attachments")
    suspend fun uploadAttachment(
        @Path("receipt_id") receiptId: String,
        @Header("X-Canonical-Body-SHA256") canonicalBodySha256: String,
        @Part("metadata") metadata: RequestBody,
        @Part encryptedFile: MultipartBody.Part
    ): Response<ApiResponseWrapper<AttachmentAck>>

    @Multipart
    @POST("/api/v1/sdk/ingest/{receipt_id}/attachments/chunks")
    suspend fun uploadAttachmentChunk(
        @Path("receipt_id") receiptId:String,
        @Header("X-Canonical-Body-SHA256") canonicalBodySha256:String,
        @Part("metadata") metadata:RequestBody,
        @Part("chunk_index") chunkIndex:RequestBody,
        @Part("total_chunks") totalChunks:RequestBody,
        @Part("chunk_sha256") chunkSha256:RequestBody,
        @Part encryptedChunk:MultipartBody.Part
    ):Response<ApiResponseWrapper<AttachmentAck>>

    @POST("/api/v1/sdk/telemetry")
    suspend fun reportTelemetry(
        @Body request: TelemetryRequest
    ): Response<ApiResponseWrapper<String>>

    @GET("/api/v1/sdk/config")
    suspend fun getRemoteConfig(
        @Query("package_name") packageName: String
    ): Response<ApiResponseWrapper<SDKRemoteConfig>>
}
