package com.feedback.service.sync

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.LongBuffer
import java.nio.file.Path
import kotlin.math.sqrt

/** Kotlin/JDK17 适配层。ONNX Runtime 与 HuggingFace 分词器都是运行期插件。 */
@Component
@ConditionalOnProperty(prefix = "app.ai.embedding", name = ["provider"], havingValue = "onnx-bge-m3")
class BgeM3OnnxEmbeddingProvider(
    @Value("\${app.ai.embedding.model-path}") modelPath: String,
    @Value("\${app.ai.embedding.onnx-file:onnx/model.onnx}") onnxFile: String,
    @Value("\${app.ai.embedding.max-length:512}") private val maxLength: Int
) : EmbeddingProvider, AutoCloseable {
    override val modelVersion = "BAAI/bge-m3-onnx-dense-v1"
    override val dimension = 1024

    private val environment: Any
    private val session: AutoCloseable
    private val tokenizer: AutoCloseable
    private val encodeMethod: java.lang.reflect.Method
    private val idsMethod: java.lang.reflect.Method
    private val attentionMethod: java.lang.reflect.Method
    private val createTensorMethod: java.lang.reflect.Method
    private val runMethod: java.lang.reflect.Method
    private val inputNames: Set<String>

    init {
        if(maxLength !in 8..8192) failed("BGE_M3_MAX_LENGTH_INVALID")
        try {
            val environmentClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            environment = environmentClass.getMethod("getEnvironment").invoke(null)
            val optionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
            val options = optionsClass.getConstructor().newInstance()
            optionsClass.getMethod("setInterOpNumThreads", Int::class.javaPrimitiveType).invoke(options, 1)
            optionsClass.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType)
                .invoke(options, maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
            session = environmentClass.getMethod("createSession", String::class.java, optionsClass)
                .invoke(environment, Path.of(modelPath).resolve(onnxFile).toString(), options) as AutoCloseable
            @Suppress("UNCHECKED_CAST")
            inputNames = session.javaClass.getMethod("getInputNames").invoke(session) as Set<String>
            if(!inputNames.containsAll(setOf("input_ids", "attention_mask"))) failed("BGE_M3_ONNX_INPUT_INVALID")
            runMethod = session.javaClass.methods.first { it.name == "run" && it.parameterCount == 1 }

            val tokenizerClass = Class.forName("ai.djl.huggingface.tokenizers.HuggingFaceTokenizer")
            tokenizer = tokenizerClass.getMethod("newInstance", Path::class.java).invoke(null, Path.of(modelPath)) as AutoCloseable
            encodeMethod = tokenizerClass.getMethod("encode", String::class.java)
            val encodingClass = Class.forName("ai.djl.huggingface.tokenizers.Encoding")
            idsMethod = encodingClass.getMethod("getIds")
            attentionMethod = encodingClass.getMethod("getAttentionMask")
            val tensorClass = Class.forName("ai.onnxruntime.OnnxTensor")
            createTensorMethod = tensorClass.methods.first {
                it.name == "createTensor" && it.parameterTypes.contentEquals(
                    arrayOf(environmentClass, LongBuffer::class.java, LongArray::class.java)
                )
            }
        } catch (error: Exception) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR,"BGE_M3_RUNTIME_LOAD_FAILED",cause=error)
        }
    }

    @Synchronized
    override fun embed(text: String): List<Float> {
        val encoded = encodeMethod.invoke(tokenizer, text.take(20_000))
        val ids = (idsMethod.invoke(encoded) as LongArray).take(maxLength).toLongArray()
        if(ids.isEmpty()) failed("BGE_M3_EMPTY_TOKENS")
        val attention = (attentionMethod.invoke(encoded) as LongArray).take(ids.size).toLongArray()
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = linkedMapOf<String, AutoCloseable>()
        try {
            tensors["input_ids"] = tensor(ids, shape)
            tensors["attention_mask"] = tensor(attention, shape)
            if ("token_type_ids" in inputNames) tensors["token_type_ids"] = tensor(LongArray(ids.size), shape)
            val result = runMethod.invoke(session, tensors) as AutoCloseable
            result.use {
                val first = result.javaClass.getMethod("get", Int::class.javaPrimitiveType).invoke(result, 0)
                val hidden = first.javaClass.getMethod("getValue").invoke(first) as Array<*>
                val batch = hidden[0] as Array<*>
                val cls = batch[0] as FloatArray
                if(cls.size != dimension) failed("BGE_M3_DIMENSION_INVALID")
                val norm = sqrt(cls.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
                return cls.map { it / norm }
            }
        } finally {
            tensors.values.forEach(AutoCloseable::close)
        }
    }

    private fun tensor(values: LongArray, shape: LongArray): AutoCloseable =
        createTensorMethod.invoke(null, environment, LongBuffer.wrap(values), shape) as AutoCloseable

    override fun close() {
        tokenizer.close()
        session.close()
    }

    private fun failed(reasonCode:String):Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}
