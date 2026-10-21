package com.feedback.sdk.capture

import android.app.Activity
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * 截图采集 + 轻量圈选标注
 *
 * - 打开反馈弹窗时可在内存中预捕获当前页面
 * - 客户端上传压缩原图、标注合成图和标注坐标 JSON
 * - 截图在加密前缩放至最长边不超过 1920px，JPEG/WebP 质量 75~80，目标≤500KB
 */
object ScreenCapture {

    /**
     * 截取完整的应用窗口。SurfaceView/视频层必须走 PixelCopy；
     * Canvas 回退则保证 API 24/25 的宿主仍然可用。
     */
    fun captureActivity(
        activity: Activity,
        maxEdgePx: Int = 1920,
        quality: Int = 75,
        completed: (ByteArray?) -> Unit
    ) {
        if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0 ||
            activity.isFinishing || activity.isDestroyed
        ) {
            completed(null)
            return
        }
        val root = activity.window.decorView
        if (root.width <= 0 || root.height <= 0) {
            root.post { captureActivity(activity, maxEdgePx, quality, completed) }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            completed(captureViewHierarchy(activity, maxEdgePx, quality))
            return
        }
        val bitmap = runCatching {
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            completed(null)
            return
        }
        PixelCopy.request(activity.window, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                completed(compress(bitmap, maxEdgePx, quality))
            } else {
                bitmap.recycle()
                completed(captureViewHierarchy(activity, maxEdgePx, quality))
            }
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * 捕获当前 Activity 视图截图，返回压缩后的 JPEG 字节
     */
    fun captureActivity(activity: Activity, maxEdgePx: Int = 1920, quality: Int = 75): ByteArray? {
        return captureViewHierarchy(activity, maxEdgePx, quality)
    }

    private fun captureViewHierarchy(activity: Activity, maxEdgePx: Int, quality: Int): ByteArray? {
        return try {
            // 否则直接绘制进程内的视图层级，会绕过 FLAG_SECURE 的
            // 视觉效果。在碰任何像素之前，
            // 先尊重宿主设置的保护。
            if ((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                return null
            }
            val rootView = activity.window.decorView.rootView
            if (rootView.width <= 0 || rootView.height <= 0) return null
            // 绘制缓存已从 Android 官方渲染路径中移除。改为直接绘制视图层级
            // 无需存储权限，且只截到宿主 Activity，
            // 这正是 SDK 期望的隐私边界。
            val bitmap = Bitmap.createBitmap(
                rootView.width,
                rootView.height,
                Bitmap.Config.ARGB_8888
            )
            rootView.draw(Canvas(bitmap))
            compress(bitmap, maxEdgePx, quality)
        } catch (e: Exception) {
            null
        }
    }

    private fun compress(bitmap: Bitmap, maxEdgePx: Int, quality: Int): ByteArray? = try {
        val scaled = scaleBitmap(bitmap, maxEdgePx)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        output.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        if (!bitmap.isRecycled) bitmap.recycle()
        null
    }

    /**
     * 将圈选标注叠加到原图上，返回合成图字节
     * @param original 原图
     * @param annotations 标注列表 (type, startX, startY, endX, endY)
     * @return Pair(合成图bytes, 标注坐标JSON)
     */
    fun annotate(
        original: Bitmap,
        annotations: List<Annotation>,
        quality: Int = 75
    ): Pair<ByteArray?, String> {
        if (annotations.isEmpty()) return Pair(null, "[]")

        val canvas = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val g = Canvas(canvas)
        g.drawBitmap(original, 0f, 0f, null)

        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        val annotationJson = StringBuilder("[")
        annotations.forEachIndexed { i, ann ->
            if (i > 0) annotationJson.append(",")
            annotationJson.append(
                """{"type":"${ann.type}","x":${ann.x},"y":${ann.y},"w":${ann.w},"h":${ann.h}}"""
            )
            g.drawRect(ann.x.toFloat(), ann.y.toFloat(),
                (ann.x + ann.w).toFloat(), (ann.y + ann.h).toFloat(), paint)
        }
        annotationJson.append("]")

        val output = ByteArrayOutputStream()
        canvas.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return Pair(output.toByteArray(), annotationJson.toString())
    }

    /** 缩放至最长边不超过 maxEdgePx */
    private fun scaleBitmap(source: Bitmap, maxEdgePx: Int): Bitmap {
        val w = source.width
        val h = source.height
        val max = maxOf(w, h)
        if (max <= maxEdgePx) return source
        val ratio = maxEdgePx.toFloat() / max
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }
}

/**
 * 圈选标注
 * @param type RECT（矩形框）、BRUSH（画笔）
 * @param x 左上角X
 * @param y 左上角Y
 * @param w 宽度
 * @param h 高度
 */
data class Annotation(
    val type: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)
