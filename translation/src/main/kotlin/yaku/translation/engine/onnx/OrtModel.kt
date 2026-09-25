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
     * Runs the session and hands every output to [block].
     *
     * The float path below carries one tensor, which suits a detector whose whole answer is a
     * probability map. One that returns boxes has three - labels, boxes and scores - and the
     * labels are integers, so they cannot come back through a FloatArray at all.
     */
    fun <T> run(inputs: Map<String, OnnxTensor>, block: (OrtSession.Result) -> T): T =
        session.run(inputs).use(block)

    /**
     * Runs the session and hands the named output to [block] as a flat float array plus its shape.
     *
     * The result is copied out before the native buffers are released, so [block] may keep it.
     */
    fun <T> run(
        inputs: Map<String, OnnxTensor>,
        outputName: String?,
        block: (data: FloatArray, shape: LongArray) -> T,
    ): T = session.run(inputs).use { results ->
        val tensor = results.tensor(outputName)
        val buffer = tensor.floatBuffer
        val data = FloatArray(buffer.remaining())
        buffer.get(data)
        block(data, tensor.info.shape)
    }

    /**
     * Runs one decoder step and returns the token with the highest logit at the final position.
     *
     * A decoder answers with logits for every position so far, `[1, steps, vocab]`, and greedy
     * decoding reads only the last row. Copying the whole tensor into an array first, as [run]
     * does, allocated a vocabulary-sized row for every step already taken - on every step, on top
     * of the copy ONNX Runtime makes itself.
     */
    fun argmaxAtLastStep(inputs: Map<String, OnnxTensor>, outputName: String?): Int =
        session.run(inputs).use { results ->
            val tensor = results.tensor(outputName)
            argmaxOfLastRow(tensor.floatBuffer, rowLength = tensor.info.shape.last().toInt())
        }

    private fun OrtSession.Result.tensor(outputName: String?): OnnxTensor {
        val value = if (outputName != null && outputNames.contains(outputName)) {
            get(outputName).orElseThrow { IllegalStateException("No output '$outputName'") }
        } else {
            get(0)
        }
        return value as? OnnxTensor ?: error("Output '${outputName ?: 0}' is not a tensor")
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

                // The CPU arena keeps every block it has ever allocated, so a run of pages
                // never gives memory back: measured across one chapter the native heap went
                // from 141MB two minutes in to 593MB at four, and the process was killed at
                // six while still in the foreground. Pages differ in size, so the arena cannot
                // reuse much of what it holds anyway, and the memory pattern planner has
                // little to plan for. Both cost some allocation speed per page and keep the
                // footprint flat, which is what lets a chapter finish at all.
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            return OrtModel(env, env.createSession(file.absolutePath, options))
        }
    }
}

/**
 * An encoder's output, copied out of its session so every decoder step can be fed the same state.
 */
internal class EncoderState(val data: FloatArray, val shape: LongArray)

/** Index of the largest of the last [rowLength] values - the final step's best token. */
internal fun argmaxOfLastRow(values: FloatBuffer, rowLength: Int): Int {
    val offset = values.limit() - rowLength
    var best = 0
    var bestValue = Float.NEGATIVE_INFINITY
    for (i in 0 until rowLength) {
        val value = values.get(offset + i)
        if (value > bestValue) {
            bestValue = value
            best = i
        }
    }
    return best
}
