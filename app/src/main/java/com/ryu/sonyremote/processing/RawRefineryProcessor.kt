package com.ryu.sonyremote.processing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.nio.FloatBuffer
import java.util.EnumSet
import kotlin.math.pow

enum class RawRefineryDenoiseModel(val label: String, internal val asset: String) {
    Light("Light", "models/rawrefinery_denoise_light.onnx"),
}

/** Runs the MIT-licensed RawRefinery RGB restoration models in linear Rec.2020. */
class RawRefineryProcessor(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val applicationContext = context.applicationContext
    private val denoiseSessions = RawRefineryDenoiseModel.entries.associateWith { model ->
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createSession(applicationContext, model.asset)
        }
    }
    private val sharpenSessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createSession(applicationContext, SHARPEN_MODEL)
    }
    private val sharpenSession by sharpenSessionDelegate

    fun apply(
        bitmap: Bitmap,
        iso: Int,
        denoiseStrength: Float,
        sharpenStrength: Float,
        denoiseModel: RawRefineryDenoiseModel = RawRefineryDenoiseModel.Light,
    ): Bitmap {
        var current = bitmap
        if (denoiseStrength > 0f) {
            current = runModel(current, denoiseSessions.getValue(denoiseModel).value, iso, denoiseStrength, affineMatch = false)
        }
        if (sharpenStrength > 0f) {
            val next = runModel(current, sharpenSession, iso, sharpenStrength, affineMatch = true)
            if (current !== bitmap) current.recycle()
            current = next
        }
        if (current !== bitmap) bitmap.recycle()
        return current
    }

    private fun runModel(
        source: Bitmap,
        session: OrtSession,
        iso: Int,
        strength: Float,
        affineMatch: Boolean,
    ): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val condition = (iso.coerceIn(0, MAX_MODEL_ISO).toFloat() / MAX_MODEL_ISO).coerceAtLeast(MIN_CONDITION)
        var coreY = 0
        while (coreY < source.height) {
            val coreHeight = minOf(CORE_SIZE, source.height - coreY)
            var coreX = 0
            while (coreX < source.width) {
                check(!Thread.currentThread().isInterrupted) { "Image processing cancelled" }
                val coreWidth = minOf(CORE_SIZE, source.width - coreX)
                processTile(source, output, session, condition, strength, affineMatch, coreX, coreY, coreWidth, coreHeight)
                coreX += CORE_SIZE
            }
            coreY += CORE_SIZE
        }
        return output
    }

    private fun processTile(
        source: Bitmap,
        destination: Bitmap,
        session: OrtSession,
        condition: Float,
        strength: Float,
        affineMatch: Boolean,
        coreX: Int,
        coreY: Int,
        coreWidth: Int,
        coreHeight: Int,
    ) {
        val tileWidth = coreWidth + 2 * PADDING
        val tileHeight = coreHeight + 2 * PADDING
        val input = FloatArray(CHANNELS * tileWidth * tileHeight)
        val argb = IntArray(tileWidth * tileHeight)
        val requestedStartX = coreX - PADDING
        val validStartX = requestedStartX.coerceAtLeast(0)
        val validEndX = (coreX + coreWidth + PADDING).coerceAtMost(source.width)
        val validWidth = validEndX - validStartX
        val row = IntArray(validWidth)
        for (y in 0 until tileHeight) {
            val sourceY = (coreY + y - PADDING).coerceIn(0, source.height - 1)
            source.getPixels(row, 0, validWidth, validStartX, sourceY, validWidth, 1)
            for (x in 0 until tileWidth) {
                val sourceX = (requestedStartX + x).coerceIn(validStartX, validEndX - 1)
                argb[y * tileWidth + x] = row[sourceX - validStartX]
            }
        }
        val plane = tileWidth * tileHeight
        for (index in argb.indices) {
            val color = argb[index]
            val r = srgbToLinear(((color ushr 16) and 0xff) / 255f)
            val g = srgbToLinear(((color ushr 8) and 0xff) / 255f)
            val b = srgbToLinear((color and 0xff) / 255f)
            input[index] = SRGB_TO_REC2020[0] * r + SRGB_TO_REC2020[1] * g + SRGB_TO_REC2020[2] * b
            input[plane + index] = SRGB_TO_REC2020[3] * r + SRGB_TO_REC2020[4] * g + SRGB_TO_REC2020[5] * b
            input[2 * plane + index] = SRGB_TO_REC2020[6] * r + SRGB_TO_REC2020[7] * g + SRGB_TO_REC2020[8] * b
        }
        val result = runSession(session, input, condition, tileWidth, tileHeight)
        if (affineMatch) affineMatch(result, input, plane)
        val outputPixels = IntArray(coreWidth * coreHeight)
        val normalizedStrength = strength.coerceIn(0f, 1f)
        for (y in 0 until coreHeight) {
            for (x in 0 until coreWidth) {
                val tileIndex = (y + PADDING) * tileWidth + x + PADDING
                val r2020 = lerp(input[tileIndex], result[tileIndex], normalizedStrength)
                val g2020 = lerp(input[plane + tileIndex], result[plane + tileIndex], normalizedStrength)
                val b2020 = lerp(input[2 * plane + tileIndex], result[2 * plane + tileIndex], normalizedStrength)
                val r = linearToSrgb(REC2020_TO_SRGB[0] * r2020 + REC2020_TO_SRGB[1] * g2020 + REC2020_TO_SRGB[2] * b2020)
                val g = linearToSrgb(REC2020_TO_SRGB[3] * r2020 + REC2020_TO_SRGB[4] * g2020 + REC2020_TO_SRGB[5] * b2020)
                val b = linearToSrgb(REC2020_TO_SRGB[6] * r2020 + REC2020_TO_SRGB[7] * g2020 + REC2020_TO_SRGB[8] * b2020)
                outputPixels[y * coreWidth + x] = (0xff shl 24) or
                    ((r * 255f + .5f).toInt().coerceIn(0, 255) shl 16) or
                    ((g * 255f + .5f).toInt().coerceIn(0, 255) shl 8) or
                    (b * 255f + .5f).toInt().coerceIn(0, 255)
            }
        }
        destination.setPixels(outputPixels, 0, coreWidth, coreX, coreY, coreWidth, coreHeight)
    }

    private fun runSession(session: OrtSession, input: FloatArray, condition: Float, width: Int, height: Int): FloatArray {
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, CHANNELS.toLong(), height.toLong(), width.toLong()),
        ).use { imageTensor ->
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(floatArrayOf(condition)),
                longArrayOf(1, 1),
            ).use { conditionTensor ->
                session.run(mapOf("image" to imageTensor, "condition" to conditionTensor)).use { result ->
                    val buffer = (result[0] as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also(buffer::get)
                }
            }
        }
    }

    private fun affineMatch(output: FloatArray, target: FloatArray, plane: Int) {
        for (channel in 0 until CHANNELS) {
            val offset = channel * plane
            var sourceMean = 0.0
            var targetMean = 0.0
            for (i in 0 until plane) {
                sourceMean += output[offset + i]
                targetMean += target[offset + i]
            }
            sourceMean /= plane
            targetMean /= plane
            var variance = 0.0
            var covariance = 0.0
            for (i in 0 until plane) {
                val sourceDelta = output[offset + i] - sourceMean
                variance += sourceDelta * sourceDelta
                covariance += sourceDelta * (target[offset + i] - targetMean)
            }
            val scale = (covariance / (variance + 1e-8)).toFloat()
            val bias = (targetMean - scale * sourceMean).toFloat()
            for (i in 0 until plane) output[offset + i] = output[offset + i] * scale + bias
        }
    }

    override fun close() {
        denoiseSessions.values.filter { it.isInitialized() }.forEach { it.value.close() }
        if (sharpenSessionDelegate.isInitialized()) sharpenSession.close()
    }

    private fun createSession(context: Context, asset: String): OrtSession {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        fun create(label: String, configure: OrtSession.SessionOptions.() -> Unit): OrtSession? {
            val result = runCatching {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.configure()
                    environment.createSession(bytes, options)
                }
            }
            result.onSuccess { Log.i(TAG, "Using $label for $asset") }
                .onFailure { Log.d(TAG, "$label unavailable for $asset", it) }
            return result.getOrNull()
        }
        val nnapiConfig: OrtSession.SessionOptions.() -> Unit = {
            addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_FP16))
        }
        return create("NNAPI, WebGPU, then CPU fallback") {
            nnapiConfig()
            addWebGPU(emptyMap())
        } ?: create("NNAPI then CPU fallback", nnapiConfig)
            ?: create("WebGPU then CPU fallback") { addWebGPU(emptyMap()) }
            ?: checkNotNull(create("ONNX Runtime CPU") {}) { "Could not initialize RawRefinery model $asset" }
    }

    private fun srgbToLinear(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(value: Float): Float {
        val safe = value.coerceAtLeast(0f)
        return (if (safe <= 0.0031308f) 12.92f * safe else 1.055f * safe.pow(1f / 2.4f) - 0.055f)
            .coerceIn(0f, 1f)
    }

    private fun lerp(start: Float, end: Float, amount: Float) = start + (end - start) * amount

    private companion object {
        const val SHARPEN_MODEL = "models/rawrefinery_deep_sharpen.onnx"
        const val CHANNELS = 3
        const val CORE_SIZE = 448
        const val PADDING = 16
        const val MAX_MODEL_ISO = 6400
        const val MIN_CONDITION = 1f / MAX_MODEL_ISO
        const val TAG = "RawRefinery"
        val SRGB_TO_REC2020 = floatArrayOf(
            .627404f, .329282f, .0433136f,
            .069097f, .91954f, .0113612f,
            .0163916f, .0880132f, .895595f,
        )
        val REC2020_TO_SRGB = floatArrayOf(
            1.660491f, -.587641f, -.072850f,
            -.124550f, 1.132900f, -.008349f,
            -.018151f, -.100579f, 1.118730f,
        )
    }
}
