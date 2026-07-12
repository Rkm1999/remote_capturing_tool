package com.ryu.sonyremote.processing

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ComputationalImageEngine {
    init {
        check(OpenCvRuntime.loaded) { "OpenCV could not be initialized on this device" }
    }

    fun newLiveNdSession(): LiveNdSession = LiveNdSession()

    fun newLiveCompositeSession(): LiveCompositeSession = LiveCompositeSession()

    fun newPanoramaSession(maxFrames: Int = DEFAULT_PANORAMA_FRAME_LIMIT): PanoramaSession =
        PanoramaSession(maxFrames)
}

class LiveNdSession internal constructor() : AutoCloseable {
    private var reference: Mat? = null
    private var averageLinear: Mat? = null
    var frameCount: Int = 0
        private set

    fun add(jpeg: ByteArray): Bitmap {
        val decoded = CvCodec.decode(jpeg, SESSION_FRAME_EDGE)
        try {
            val aligned = if (reference == null) decoded.clone() else FrameAligner.align(requireNotNull(reference), decoded)
            try {
                val linear = CvCodec.toLinear(aligned)
                try {
                    if (averageLinear == null) {
                        reference = aligned.clone()
                        averageLinear = linear.clone()
                        frameCount = 1
                    } else {
                        val nextCount = frameCount + 1
                        Core.addWeighted(
                            requireNotNull(averageLinear),
                            frameCount.toDouble() / nextCount,
                            linear,
                            1.0 / nextCount,
                            0.0,
                            requireNotNull(averageLinear),
                        )
                        frameCount = nextCount
                    }
                    return CvCodec.previewFromLinear(requireNotNull(averageLinear))
                } finally {
                    linear.release()
                }
            } finally {
                aligned.release()
            }
        } finally {
            decoded.release()
        }
    }

    fun encodeJpeg(): ByteArray {
        check(frameCount > 0) { "Live ND has no frames" }
        val display = CvCodec.fromLinear(requireNotNull(averageLinear))
        return try {
            CvCodec.encode(display)
        } finally {
            display.release()
        }
    }

    override fun close() {
        reference?.release()
        averageLinear?.release()
        reference = null
        averageLinear = null
        frameCount = 0
    }
}

class LiveCompositeSession internal constructor() : AutoCloseable {
    private var reference: Mat? = null
    private var baseLuminance: Mat? = null
    private var compositeLinear: Mat? = null
    private var compositeLuminance: Mat? = null
    var frameCount: Int = 0
        private set

    fun add(jpeg: ByteArray): Bitmap {
        val decoded = CvCodec.decode(jpeg, SESSION_FRAME_EDGE)
        try {
            val aligned = if (reference == null) decoded.clone() else FrameAligner.align(requireNotNull(reference), decoded)
            try {
                val linear = CvCodec.toLinear(aligned)
                try {
                    if (compositeLinear == null) {
                        reference = aligned.clone()
                        compositeLinear = linear.clone()
                        baseLuminance = luminance(linear)
                        compositeLuminance = requireNotNull(baseLuminance).clone()
                        frameCount = 1
                    } else {
                        mergeBrighterFrame(linear)
                        frameCount++
                    }
                    return CvCodec.previewFromLinear(requireNotNull(compositeLinear))
                } finally {
                    linear.release()
                }
            } finally {
                aligned.release()
            }
        } finally {
            decoded.release()
        }
    }

    private fun mergeBrighterFrame(frameLinear: Mat) {
        val frameLuminance = luminance(frameLinear)
        val baseDelta = Mat()
        val aboveNoise = Mat()
        val brighterThanComposite = Mat()
        val updateMask = Mat()
        try {
            Core.subtract(frameLuminance, requireNotNull(baseLuminance), baseDelta)
            Core.compare(baseDelta, Scalar(COMPOSITE_NOISE_THRESHOLD), aboveNoise, Core.CMP_GT)
            Core.compare(
                frameLuminance,
                requireNotNull(compositeLuminance),
                brighterThanComposite,
                Core.CMP_GT,
            )
            Core.bitwise_and(aboveNoise, brighterThanComposite, updateMask)
            frameLinear.copyTo(requireNotNull(compositeLinear), updateMask)
            frameLuminance.copyTo(requireNotNull(compositeLuminance), updateMask)
        } finally {
            frameLuminance.release()
            baseDelta.release()
            aboveNoise.release()
            brighterThanComposite.release()
            updateMask.release()
        }
    }

    fun encodeJpeg(): ByteArray {
        check(frameCount > 0) { "Live Composite has no frames" }
        val display = CvCodec.fromLinear(requireNotNull(compositeLinear))
        return try {
            CvCodec.encode(display)
        } finally {
            display.release()
        }
    }

    override fun close() {
        reference?.release()
        baseLuminance?.release()
        compositeLinear?.release()
        compositeLuminance?.release()
        reference = null
        baseLuminance = null
        compositeLinear = null
        compositeLuminance = null
        frameCount = 0
    }

    private fun luminance(linearBgr: Mat): Mat = Mat().also {
        Imgproc.cvtColor(linearBgr, it, Imgproc.COLOR_BGR2GRAY)
    }
}

class PanoramaSession internal constructor(private val maxFrames: Int) : AutoCloseable {
    private val images = mutableListOf<Mat>()
    private val transforms = mutableListOf<Mat>()
    private var panorama: Mat? = null

    val frameCount: Int get() = images.size

    fun add(jpeg: ByteArray): Bitmap {
        check(images.size < maxFrames) {
            "Panorama reached this device's $maxFrames-frame memory limit"
        }
        val frame = CvCodec.decode(jpeg, PANORAMA_FRAME_EDGE)
        var transform: Mat? = null
        var ownershipTransferred = false
        try {
            transform = if (images.isEmpty()) {
                Mat.eye(3, 3, CvType.CV_64F)
            } else {
                val newToPrevious = PanoramaMatcher.findHomography(frame, images.last())
                try {
                    Mat().also { combined ->
                        val empty = Mat()
                        try {
                            Core.gemm(transforms.last(), newToPrevious, 1.0, empty, 0.0, combined)
                        } finally {
                            empty.release()
                        }
                    }
                } finally {
                    newToPrevious.release()
                }
            }
            images += frame
            transforms += requireNotNull(transform)
            ownershipTransferred = true
            var blended: Mat? = null
            try {
                blended = PanoramaBlender.render(images, transforms)
                val preview = CvCodec.toBitmap(blended, PANORAMA_PREVIEW_EDGE)
                panorama?.release()
                panorama = blended
                blended = null
                return preview
            } catch (error: Throwable) {
                images.removeAt(images.lastIndex).release()
                transforms.removeAt(transforms.lastIndex).release()
                throw error
            } finally {
                blended?.release()
            }
        } finally {
            if (!ownershipTransferred) {
                frame.release()
                transform?.release()
            }
        }
    }

    fun encodeJpeg(): ByteArray {
        check(images.size >= 2) { "Take at least two panorama frames" }
        return CvCodec.encode(requireNotNull(panorama) { "Panorama is not ready" })
    }

    fun geometry(): List<PanoramaFrameGeometry> = images.indices.map { index ->
        val values = DoubleArray(9)
        transforms[index].get(0, 0, values)
        PanoramaFrameGeometry(
            previewWidth = images[index].cols(),
            previewHeight = images[index].rows(),
            transform = values,
        )
    }

    override fun close() {
        images.forEach(Mat::release)
        transforms.forEach(Mat::release)
        panorama?.release()
        images.clear()
        transforms.clear()
        panorama = null
    }
}

data class PanoramaFrameGeometry(
    val previewWidth: Int,
    val previewHeight: Int,
    val transform: DoubleArray,
) {
    init {
        require(previewWidth > 0 && previewHeight > 0)
        require(transform.size == 9)
    }
}

class PanoramaAlignmentException(message: String) : IllegalArgumentException(message)

class FrameAlignmentException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

private object OpenCvRuntime {
    val loaded: Boolean by lazy { OpenCVLoader.initLocal() }
}

private object CvCodec {
    fun decode(jpeg: ByteArray, maxEdge: Int): Mat {
        val encoded = Mat(1, jpeg.size, CvType.CV_8UC1)
        encoded.put(0, 0, jpeg)
        val decoded = try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
        check(!decoded.empty()) { "Could not decode captured JPEG" }
        val longest = max(decoded.cols(), decoded.rows())
        if (longest <= maxEdge) return decoded
        val scale = maxEdge.toDouble() / longest
        val resized = Mat()
        Imgproc.resize(decoded, resized, Size(decoded.cols() * scale, decoded.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        decoded.release()
        return resized
    }

    fun toLinear(bgr: Mat): Mat {
        val normalized = Mat()
        bgr.convertTo(normalized, CvType.CV_32FC3, 1.0 / 255.0)
        Core.pow(normalized, SRGB_GAMMA, normalized)
        return normalized
    }

    fun fromLinear(linearBgr: Mat): Mat {
        val gamma = Mat()
        Core.pow(linearBgr, 1.0 / SRGB_GAMMA, gamma)
        gamma.convertTo(gamma, CvType.CV_8UC3, 255.0)
        return gamma
    }

    fun previewFromLinear(linearBgr: Mat): Bitmap {
        val display = fromLinear(linearBgr)
        return try {
            toBitmap(display, SESSION_PREVIEW_EDGE)
        } finally {
            display.release()
        }
    }

    fun toBitmap(bgr: Mat, maxEdge: Int): Bitmap {
        val longest = max(bgr.cols(), bgr.rows())
        val display = if (longest <= maxEdge) {
            bgr
        } else {
            val scale = maxEdge.toDouble() / longest
            Mat().also {
                Imgproc.resize(bgr, it, Size(bgr.cols() * scale, bgr.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
            }
        }
        val rgba = Mat()
        return try {
            Imgproc.cvtColor(display, rgba, Imgproc.COLOR_BGR2RGBA)
            Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(rgba, it)
            }
        } finally {
            rgba.release()
            if (display !== bgr) display.release()
        }
    }

    fun encode(bgr: Mat): ByteArray {
        val output = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        return try {
            check(Imgcodecs.imencode(".jpg", bgr, output, params)) { "Could not encode processed JPEG" }
            output.toArray()
        } finally {
            output.release()
            params.release()
        }
    }
}

private object FrameAligner {
    fun align(reference: Mat, input: Mat): Mat {
        val sameSize = if (reference.size() == input.size()) {
            input
        } else {
            Mat().also { Imgproc.resize(input, it, reference.size(), 0.0, 0.0, Imgproc.INTER_AREA) }
        }
        val referenceGray = grayForAlignment(reference)
        val inputGray = grayForAlignment(sameSize)
        val warp = Mat.eye(2, 3, CvType.CV_32F)
        return try {
            val score = Video.findTransformECC(
                referenceGray,
                inputGray,
                warp,
                Video.MOTION_EUCLIDEAN,
                TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, ALIGN_ITERATIONS, ALIGN_EPSILON),
            )
            if (!score.isFinite() || score < MIN_ALIGNMENT_SCORE) {
                throw FrameAlignmentException("Hold the camera steadier for the next frame")
            }
            val scale = referenceGray.cols().toDouble() / reference.cols()
            val values = FloatArray(6)
            warp.get(0, 0, values)
            values[2] = (values[2] / scale).toFloat()
            values[5] = (values[5] / scale).toFloat()
            warp.put(0, 0, values)
            Mat().also {
                Imgproc.warpAffine(
                    sameSize,
                    it,
                    warp,
                    reference.size(),
                    Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP,
                    Core.BORDER_REFLECT,
                )
            }
        } catch (error: FrameAlignmentException) {
            throw error
        } catch (error: RuntimeException) {
            throw FrameAlignmentException("This frame could not be aligned", error)
        } finally {
            referenceGray.release()
            inputGray.release()
            warp.release()
            if (sameSize !== input) sameSize.release()
        }
    }

    private fun grayForAlignment(source: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
        val longest = max(gray.cols(), gray.rows())
        if (longest <= ALIGN_EDGE) return gray
        val scale = ALIGN_EDGE.toDouble() / longest
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(gray.cols() * scale, gray.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()
        return resized
    }
}

private object PanoramaMatcher {
    fun findHomography(source: Mat, destination: Mat): Mat {
        val sourceGray = Mat()
        val destinationGray = Mat()
        val sourceKeys = MatOfKeyPoint()
        val destinationKeys = MatOfKeyPoint()
        val sourceDescriptors = Mat()
        val destinationDescriptors = Mat()
        val orb = ORB.create(PANORAMA_FEATURES)
        try {
            Imgproc.cvtColor(source, sourceGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(destination, destinationGray, Imgproc.COLOR_BGR2GRAY)
            orb.detectAndCompute(sourceGray, Mat(), sourceKeys, sourceDescriptors)
            orb.detectAndCompute(destinationGray, Mat(), destinationKeys, destinationDescriptors)
            if (sourceDescriptors.empty() || destinationDescriptors.empty()) {
                throw PanoramaAlignmentException("This frame has too little detail to align")
            }
            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val matches = mutableListOf<MatOfDMatch>()
            try {
                matcher.knnMatch(sourceDescriptors, destinationDescriptors, matches, 2)
                val accepted = matches.mapNotNull { pair ->
                    val candidates = pair.toArray()
                    candidates.firstOrNull()?.takeIf {
                        candidates.size >= 2 && it.distance < PANORAMA_RATIO * candidates[1].distance
                    }
                }
                if (accepted.size < MIN_PANORAMA_MATCHES) {
                    throw PanoramaAlignmentException("Overlap this frame more with the previous image")
                }
                val sourcePoints = sourceKeys.toArray()
                val destinationPoints = destinationKeys.toArray()
                val sourceMatched = MatOfPoint2f(
                    *accepted.map { sourcePoints[it.queryIdx].pt }.toTypedArray(),
                )
                val destinationMatched = MatOfPoint2f(
                    *accepted.map { destinationPoints[it.trainIdx].pt }.toTypedArray(),
                )
                val inlierMask = Mat()
                try {
                    val homography = Calib3d.findHomography(
                        sourceMatched,
                        destinationMatched,
                        Calib3d.RANSAC,
                        PANORAMA_RANSAC_THRESHOLD,
                        inlierMask,
                        PANORAMA_RANSAC_ITERATIONS,
                        PANORAMA_RANSAC_CONFIDENCE,
                    )
                    validateHomography(homography, inlierMask, accepted.size, source.size())
                    return homography
                } finally {
                    sourceMatched.release()
                    destinationMatched.release()
                    inlierMask.release()
                }
            } finally {
                matches.forEach(Mat::release)
                matcher.clear()
            }
        } finally {
            sourceGray.release()
            destinationGray.release()
            sourceKeys.release()
            destinationKeys.release()
            sourceDescriptors.release()
            destinationDescriptors.release()
            orb.clear()
        }
    }

    private fun validateHomography(homography: Mat, inlierMask: Mat, matches: Int, sourceSize: Size) {
        if (homography.empty()) throw PanoramaAlignmentException("The panorama frame could not be aligned")
        val inliers = Core.countNonZero(inlierMask)
        if (inliers < MIN_PANORAMA_INLIERS || inliers.toDouble() / matches < MIN_PANORAMA_INLIER_RATIO) {
            homography.release()
            throw PanoramaAlignmentException("Overlap this frame more with the previous image")
        }
        val corners = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(sourceSize.width, 0.0),
            Point(sourceSize.width, sourceSize.height),
            Point(0.0, sourceSize.height),
        )
        val projected = MatOfPoint2f()
        try {
            Core.perspectiveTransform(corners, projected, homography)
            val points = projected.toArray()
            if (points.any { !it.x.isFinite() || !it.y.isFinite() }) {
                homography.release()
                throw PanoramaAlignmentException("The panorama transform was unstable")
            }
            val area = polygonArea(points)
            val sourceArea = sourceSize.width * sourceSize.height
            if (area / sourceArea !in MIN_PANORAMA_SCALE..MAX_PANORAMA_SCALE) {
                homography.release()
                throw PanoramaAlignmentException("Move more steadily between panorama frames")
            }
        } finally {
            corners.release()
            projected.release()
        }
    }

    private fun polygonArea(points: Array<Point>): Double {
        var twiceArea = 0.0
        points.indices.forEach { index ->
            val next = points[(index + 1) % points.size]
            twiceArea += points[index].x * next.y - next.x * points[index].y
        }
        return abs(twiceArea) / 2.0
    }
}

private object PanoramaBlender {
    fun render(images: List<Mat>, transforms: List<Mat>): Mat {
        require(images.isNotEmpty() && images.size == transforms.size)
        val bounds = bounds(images, transforms)
        val rawWidth = (bounds.maxX - bounds.minX).coerceAtLeast(1.0)
        val rawHeight = (bounds.maxY - bounds.minY).coerceAtLeast(1.0)
        val edgeScale = minOf(MAX_PANORAMA_EDGE / rawWidth, MAX_PANORAMA_EDGE / rawHeight, 1.0)
        val pixelScale = minOf(sqrt(MAX_PANORAMA_PIXELS / (rawWidth * rawHeight)), 1.0)
        val previewScale = minOf(edgeScale, pixelScale)
        val width = ceil(rawWidth * previewScale).toInt().coerceAtLeast(1)
        val height = ceil(rawHeight * previewScale).toInt().coerceAtLeast(1)
        val translation = Mat.eye(3, 3, CvType.CV_64F)
        translation.put(0, 0, previewScale)
        translation.put(1, 1, previewScale)
        translation.put(0, 2, -bounds.minX * previewScale)
        translation.put(1, 2, -bounds.minY * previewScale)
        val weightedSum = Mat.zeros(height, width, CvType.CV_32FC3)
        val weightSum = Mat.zeros(height, width, CvType.CV_32FC1)
        try {
            images.indices.forEach { index ->
                blendImage(images[index], transforms[index], translation, weightedSum, weightSum)
            }
            val safeWeights = Mat()
            val channels = mutableListOf<Mat>()
            try {
                Core.max(weightSum, Scalar(MIN_BLEND_WEIGHT), safeWeights)
                Core.split(weightedSum, channels)
                channels.forEach { Core.divide(it, safeWeights, it) }
                Core.merge(channels, weightedSum)
                return Mat().also { weightedSum.convertTo(it, CvType.CV_8UC3) }
            } finally {
                safeWeights.release()
                channels.forEach(Mat::release)
            }
        } finally {
            translation.release()
            weightedSum.release()
            weightSum.release()
        }
    }

    private fun blendImage(
        image: Mat,
        transform: Mat,
        translation: Mat,
        weightedSum: Mat,
        weightSum: Mat,
    ) {
        val canvasTransform = Mat()
        val sourceWeight = featherWeight(image.size())
        val warpedWeight = Mat.zeros(weightSum.size(), CvType.CV_32FC1)
        val warpedImage = Mat.zeros(weightedSum.size(), CvType.CV_8UC3)
        val imageFloat = Mat()
        val weight3 = Mat()
        val empty = Mat()
        try {
            Core.gemm(translation, transform, 1.0, empty, 0.0, canvasTransform)
            Imgproc.warpPerspective(
                image,
                warpedImage,
                canvasTransform,
                weightedSum.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
            )
            Imgproc.warpPerspective(
                sourceWeight,
                warpedWeight,
                canvasTransform,
                weightSum.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
            )
            warpedImage.convertTo(imageFloat, CvType.CV_32FC3)
            Core.merge(listOf(warpedWeight, warpedWeight, warpedWeight), weight3)
            Core.multiply(imageFloat, weight3, imageFloat)
            Core.add(weightedSum, imageFloat, weightedSum)
            Core.add(weightSum, warpedWeight, weightSum)
        } finally {
            canvasTransform.release()
            sourceWeight.release()
            warpedWeight.release()
            warpedImage.release()
            imageFloat.release()
            weight3.release()
            empty.release()
        }
    }

    private fun featherWeight(size: Size): Mat {
        val mask = Mat(size, CvType.CV_8UC1, Scalar(255.0))
        listOf(mask.row(0), mask.row(mask.rows() - 1), mask.col(0), mask.col(mask.cols() - 1)).forEach {
            it.setTo(Scalar(0.0))
            it.release()
        }
        val distance = Mat()
        val normalized = Mat()
        try {
            Imgproc.distanceTransform(mask, distance, Imgproc.DIST_L2, 3)
            Core.normalize(distance, normalized, MIN_FEATHER_WEIGHT, 1.0, Core.NORM_MINMAX)
            return normalized.clone()
        } finally {
            mask.release()
            distance.release()
            normalized.release()
        }
    }

    private fun bounds(images: List<Mat>, transforms: List<Mat>): MosaicBounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        images.indices.forEach { index ->
            val image = images[index]
            val corners = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(image.cols().toDouble(), 0.0),
                Point(image.cols().toDouble(), image.rows().toDouble()),
                Point(0.0, image.rows().toDouble()),
            )
            val projected = MatOfPoint2f()
            try {
                Core.perspectiveTransform(corners, projected, transforms[index])
                projected.toArray().forEach { point ->
                    minX = min(minX, point.x)
                    minY = min(minY, point.y)
                    maxX = max(maxX, point.x)
                    maxY = max(maxY, point.y)
                }
            } finally {
                corners.release()
                projected.release()
            }
        }
        check(listOf(minX, minY, maxX, maxY).all(Double::isFinite)) { "Invalid panorama bounds" }
        return MosaicBounds(floor(minX), floor(minY), ceil(maxX), ceil(maxY))
    }

    private data class MosaicBounds(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    )
}

private const val SESSION_FRAME_EDGE = 2048
private const val SESSION_PREVIEW_EDGE = 1200
private const val PANORAMA_FRAME_EDGE = 2048
private const val PANORAMA_PREVIEW_EDGE = 1600
private const val JPEG_QUALITY = 95
private const val SRGB_GAMMA = 2.2
private const val COMPOSITE_NOISE_THRESHOLD = 0.015
private const val ALIGN_EDGE = 800
private const val ALIGN_ITERATIONS = 60
private const val ALIGN_EPSILON = 0.0005
private const val MIN_ALIGNMENT_SCORE = 0.55
private const val PANORAMA_FEATURES = 5000
private const val PANORAMA_RATIO = 0.75f
private const val MIN_PANORAMA_MATCHES = 18
private const val MIN_PANORAMA_INLIERS = 12
private const val MIN_PANORAMA_INLIER_RATIO = 0.3
private const val PANORAMA_RANSAC_THRESHOLD = 3.0
private const val PANORAMA_RANSAC_ITERATIONS = 2500
private const val PANORAMA_RANSAC_CONFIDENCE = 0.995
private const val MIN_PANORAMA_SCALE = 0.25
private const val MAX_PANORAMA_SCALE = 4.0
private const val MAX_PANORAMA_EDGE = 6_000
private const val MAX_PANORAMA_PIXELS = 6_000_000L
private const val DEFAULT_PANORAMA_FRAME_LIMIT = 12
private const val MIN_BLEND_WEIGHT = 0.001
private const val MIN_FEATHER_WEIGHT = 0.02
