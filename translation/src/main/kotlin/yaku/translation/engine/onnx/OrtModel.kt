package yaku.translation.engine.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Thin wrapper over an ONNX Runtime session.
 *
 * Sessions are expensive to create (tens to hundreds of ms) and hold a large native allocation,
 * so the pipeline creates them once and keeps them alive for as long as translation is switched
 * on, then closes them when the reader exits.
 */
class OrtModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : Closeable {

    val inputNames: Set<String> get() = session.inputNames
    val outputNames: Set<String> get() = session.outputNames

    /**
     * Picks the first of [candidates] the graph actually exposes, falling back to the first
     * declared input. Export toolchains disagree on naming (`pixel_values` vs `input`), and a
     * pack author should not have to spell every name out in the manifest to use a stock export.
     */
    fun resolveInput(vararg candidates: String): String =
        candidates.firstOrNull { it in inputNames } ?: inputNames.first()

    fun resolveOutput(vararg candidates: String): String =
        candidates.firstOrNull { it in outputNames } ?: outputNames.first()

    fun hasInput(name: String): Boolean = name in inputNames

    fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    fun longTensor(data: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    /**
     * Runs the session and hands the named output to [block] as a flat float array plus its shape.
     *
     * The result is copied out before the native buffers are released, so [block] may keep it.
     */
    fun <T> run(
        inputs: Map<String, OnnxTensor>,
        outputName: String?,
        block: (data: FloatArray, shape: LongArray) -> T,
    ): T {
        session.run(inputs).use { results ->
            val value = if (outputName != null && outputNames.contains(outputName)) {
                results.get(outputName).orElseThrow { IllegalStateException("No output '$outputName'") }
            } else {
                results.get(0)
            }
            val tensor = value as? OnnxTensor
                ?: error("Output '${outputName ?: 0}' is not a tensor")
            val shape = tensor.info.shape
            val buffer = tensor.floatBuffer
            val data = FloatArray(buffer.remaining())
            buffer.get(data)
            return block(data, shape)
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        fun open(file: File, threads: Int): OrtModel {
            require(file.exists()) { "Model file missing: ${file.name}" }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads.coerceAtLeast(1))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return OrtModel(env, env.createSession(file.absolutePath, options))
        }
    }
}
