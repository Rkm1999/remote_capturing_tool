package com.ryu.sonyremote.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

data class PanoramaSourceDimensions(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

data class PanoramaResourceBudget(
    val maxHeapBytes: Long,
    val availableDiskBytes: Long,
    val maxOutputEdge: Int = Int.MAX_VALUE,
    val maxOutputPixels: Long = Long.MAX_VALUE,
) {
    init {
        require(maxHeapBytes > 0 && availableDiskBytes > 0)
        require(maxOutputEdge > 0 && maxOutputPixels > 0)
    }
}

data class PanoramaRenderPlan(
    val width: Int,
    val height: Int,
    val scale: Double,
    val minX: Double,
    val minY: Double,
    val estimatedPeakBytes: Long,
    val originalResolution: Boolean,
)

object PanoramaRenderPlanner {
    fun plan(
        geometry: List<PanoramaFrameGeometry>,
        sources: List<PanoramaSourceDimensions>,
        budget: PanoramaResourceBudget,
    ): PanoramaRenderPlan {
        require(geometry.size >= 2 && geometry.size == sources.size)
        val naturalScale = min(
            sources.first().width.toDouble() / geometry.first().previewWidth,
            sources.first().height.toDouble() / geometry.first().previewHeight,
        )
        val naturalBounds = bounds(geometry, sources, naturalScale)
        val naturalWidth = (naturalBounds.maxX - naturalBounds.minX).coerceAtLeast(1.0)
        val naturalHeight = (naturalBounds.maxY - naturalBounds.minY).coerceAtLeast(1.0)
        val naturalPixels = naturalWidth * naturalHeight
        val largestSourceBytes = sources.maxOf { it.width.toLong() * it.height * BYTES_PER_PIXEL }
        val heapForOutput = (budget.maxHeapBytes * HEAP_FRACTION).toLong() - largestSourceBytes
        check(heapForOutput > MIN_OUTPUT_BYTES) {
            "Not enough free image memory for a high-resolution panorama"
        }
        val heapPixels = heapForOutput / OUTPUT_PEAK_BYTES_PER_PIXEL
        val diskPixels = budget.availableDiskBytes / TEMP_BYTES_PER_PIXEL
        val allowedPixels = minOf(heapPixels, diskPixels, budget.maxOutputPixels).coerceAtLeast(1)
        val edgeScale = min(
            budget.maxOutputEdge / naturalWidth,
            budget.maxOutputEdge / naturalHeight,
        ).coerceAtMost(1.0)
        val pixelScale = sqrt(allowedPixels / naturalPixels).coerceAtMost(1.0)
        val outputScale = min(edgeScale, pixelScale).coerceAtMost(1.0)
        check(outputScale >= MIN_RENDER_SCALE) {
            "Panorama exceeds this device's safe memory or storage limit"
        }
        val width = floor(naturalWidth * outputScale).toInt().coerceAtLeast(1)
        val height = floor(naturalHeight * outputScale).toInt().coerceAtLeast(1)
        val estimated = width.toLong() * height * OUTPUT_PEAK_BYTES_PER_PIXEL + largestSourceBytes
        return PanoramaRenderPlan(
            width = width,
            height = height,
            scale = naturalScale * outputScale,
            minX = naturalBounds.minX * outputScale,
            minY = naturalBounds.minY * outputScale,
            estimatedPeakBytes = estimated,
            originalResolution = outputScale >= 0.999,
        )
    }

    private fun bounds(
        geometry: List<PanoramaFrameGeometry>,
        sources: List<PanoramaSourceDimensions>,
        globalScale: Double,
    ): Bounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        geometry.indices.forEach { index ->
            val item = geometry[index]
            val source = sources[index]
            val sx = item.previewWidth.toDouble() / source.width
            val sy = item.previewHeight.toDouble() / source.height
            arrayOf(
                0.0 to 0.0,
                source.width.toDouble() to 0.0,
                source.width.toDouble() to source.height.toDouble(),
                0.0 to source.height.toDouble(),
            ).forEach { (x, y) ->
                val point = project(item.transform, x * sx, y * sy)
                val px = point.first * globalScale
                val py = point.second * globalScale
                minX = minOf(minX, px)
                minY = minOf(minY, py)
                maxX = maxOf(maxX, px)
                maxY = maxOf(maxY, py)
            }
        }
        check(listOf(minX, minY, maxX, maxY).all(Double::isFinite)) {
            "Panorama transforms produced invalid output bounds"
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private fun project(matrix: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val w = matrix[6] * x + matrix[7] * y + matrix[8]
        check(kotlin.math.abs(w) > 1e-9) { "Panorama transform is singular" }
        return (matrix[0] * x + matrix[1] * y + matrix[2]) / w to
            (matrix[3] * x + matrix[4] * y + matrix[5]) / w
    }

    private data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

    private const val BYTES_PER_PIXEL = 4L
    private const val OUTPUT_PEAK_BYTES_PER_PIXEL = 6L
    private const val TEMP_BYTES_PER_PIXEL = 2L
    private const val HEAP_FRACTION = 0.55
    private const val MIN_OUTPUT_BYTES = 4L * 1024 * 1024
    private const val MIN_RENDER_SCALE = 0.05
}

class PanoramaFinalRenderer(
    private val geometry: List<PanoramaFrameGeometry>,
    val plan: PanoramaRenderPlan,
    private val imageProcessor: JpegImageProcessor,
) : Closeable {
    private var output: Bitmap? = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(requireNotNull(output))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun addSource(index: Int, jpeg: ByteArray, dimensions: PanoramaSourceDimensions) {
        check(index in geometry.indices)
        val bitmap = imageProcessor.decode(jpeg, maxEdge = null, mutable = false)
        try {
            val item = geometry[index]
            val sx = item.previewWidth.toDouble() / dimensions.width
            val sy = item.previewHeight.toDouble() / dimensions.height
            val h = item.transform
            val matrix = Matrix().apply {
                setValues(floatArrayOf(
                    (plan.scale * h[0] * sx).toFloat(),
                    (plan.scale * h[1] * sy).toFloat(),
                    (plan.scale * h[2] - plan.minX).toFloat(),
                    (plan.scale * h[3] * sx).toFloat(),
                    (plan.scale * h[4] * sy).toFloat(),
                    (plan.scale * h[5] - plan.minY).toFloat(),
                    (h[6] * sx).toFloat(),
                    (h[7] * sy).toFloat(),
                    h[8].toFloat(),
                ))
            }
            paint.alpha = 255
            canvas.drawBitmap(bitmap, matrix, paint)
        } finally {
            bitmap.recycle()
        }
    }

    fun encodeJpeg(): ByteArray = imageProcessor.encode(requireNotNull(output))

    override fun close() {
        output?.recycle()
        output = null
    }
}
