package com.ryu.sonyremote.processing

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class LutPreset(val label: String) {
    Neutral("Original"),
    Cinema("Cinema"),
    Warm("Warm"),
    Cool("Cool"),
    Mono("Mono"),
    Fade("Fade"),
    Punch("Punch"),
    Teal("Teal"),
}

/** A license-clean, in-memory RGB 3D LUT with trilinear sampling. */
class CubeLut private constructor(
    val size: Int,
    private val values: FloatArray,
) {
    private val byteAxisLow = IntArray(256)
    private val byteAxisHigh = IntArray(256)
    private val byteAxisFraction = FloatArray(256)

    init {
        require(size >= 2) { "A 3D LUT must contain at least two samples per axis" }
        require(values.size == size * size * size * CHANNELS) { "Invalid 3D LUT data size" }
        repeat(256) { value ->
            val position = value / 255f * (size - 1)
            val low = floor(position).toInt().coerceIn(0, size - 1)
            byteAxisLow[value] = low
            byteAxisHigh[value] = min(low + 1, size - 1)
            byteAxisFraction[value] = position - low
        }
    }

    fun mapArgb(pixel: Int): Int {
        return mapArgb(pixel, 1f)
    }

    fun mapArgb(pixel: Int, intensity: Float): Int {
        val amount = intensity.coerceIn(0f, 1f)
        val alpha = pixel ushr 24 and 0xff
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val mappedRed = toByte(sampleByte(red, green, blue, 0))
        val mappedGreen = toByte(sampleByte(red, green, blue, 1))
        val mappedBlue = toByte(sampleByte(red, green, blue, 2))
        return (alpha shl 24) or
            (mixByte(red, mappedRed, amount) shl 16) or
            (mixByte(green, mappedGreen, amount) shl 8) or
            mixByte(blue, mappedBlue, amount)
    }

    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val r = axis(red)
        val g = axis(green)
        val b = axis(blue)
        val output = FloatArray(CHANNELS)
        for (channel in 0 until CHANNELS) {
            output[channel] = interpolate(r, g, b, channel)
        }
        return output
    }

    private fun sampleByte(red: Int, green: Int, blue: Int, channel: Int): Float {
        val redLow = byteAxisLow[red]
        val redHigh = byteAxisHigh[red]
        val greenLow = byteAxisLow[green]
        val greenHigh = byteAxisHigh[green]
        val blueLow = byteAxisLow[blue]
        val blueHigh = byteAxisHigh[blue]
        val redFraction = byteAxisFraction[red]
        val greenFraction = byteAxisFraction[green]
        val blueFraction = byteAxisFraction[blue]
        val c00 = mix(
            value(redLow, greenLow, blueLow, channel),
            value(redHigh, greenLow, blueLow, channel),
            redFraction,
        )
        val c10 = mix(
            value(redLow, greenHigh, blueLow, channel),
            value(redHigh, greenHigh, blueLow, channel),
            redFraction,
        )
        val c01 = mix(
            value(redLow, greenLow, blueHigh, channel),
            value(redHigh, greenLow, blueHigh, channel),
            redFraction,
        )
        val c11 = mix(
            value(redLow, greenHigh, blueHigh, channel),
            value(redHigh, greenHigh, blueHigh, channel),
            redFraction,
        )
        return mix(
            mix(c00, c10, greenFraction),
            mix(c01, c11, greenFraction),
            blueFraction,
        ).coerceIn(0f, 1f)
    }

    private fun interpolate(r: AxisPosition, g: AxisPosition, b: AxisPosition, channel: Int): Float {
        val c00 = mix(
            value(r.low, g.low, b.low, channel),
            value(r.high, g.low, b.low, channel),
            r.fraction,
        )
        val c10 = mix(
            value(r.low, g.high, b.low, channel),
            value(r.high, g.high, b.low, channel),
            r.fraction,
        )
        val c01 = mix(
            value(r.low, g.low, b.high, channel),
            value(r.high, g.low, b.high, channel),
            r.fraction,
        )
        val c11 = mix(
            value(r.low, g.high, b.high, channel),
            value(r.high, g.high, b.high, channel),
            r.fraction,
        )
        return mix(
            mix(c00, c10, g.fraction),
            mix(c01, c11, g.fraction),
            b.fraction,
        ).coerceIn(0f, 1f)
    }

    private fun axis(value: Float): AxisPosition {
        val position = value.coerceIn(0f, 1f) * (size - 1)
        val low = floor(position).toInt().coerceIn(0, size - 1)
        val high = min(low + 1, size - 1)
        return AxisPosition(low, high, position - low)
    }

    private fun value(red: Int, green: Int, blue: Int, channel: Int): Float =
        values[(((blue * size + green) * size + red) * CHANNELS) + channel]

    private data class AxisPosition(val low: Int, val high: Int, val fraction: Float)

    companion object {
        private const val CHANNELS = 3
        private const val DEFAULT_SIZE = 17

        fun forPreset(preset: LutPreset): CubeLut = generate(DEFAULT_SIZE) { r, g, b ->
            when (preset) {
                LutPreset.Neutral -> floatArrayOf(r, g, b)
                LutPreset.Cinema -> {
                    val luminance = luminance(r, g, b)
                    val contrast = { value: Float ->
                        ((value - 0.5f) * 1.08f + 0.5f).coerceIn(0f, 1f)
                    }
                    val red = contrast(0.94f * r + 0.06f * luminance)
                    val green = contrast(0.98f * g + 0.02f * luminance)
                    val blue = contrast(0.92f * b + 0.08f * luminance)
                    val shadow = (1f - luminance).pow(2)
                    val highlight = luminance.pow(2)
                    floatArrayOf(
                        red + 0.045f * highlight,
                        green + 0.018f * highlight,
                        blue + 0.05f * shadow - 0.025f * highlight,
                    )
                }
                LutPreset.Warm -> floatArrayOf(
                    r.pow(0.96f) * 1.035f,
                    g * 1.005f,
                    b.pow(1.04f) * 0.94f,
                )
                LutPreset.Cool -> floatArrayOf(
                    r.pow(1.03f) * 0.95f,
                    g * 1.005f,
                    b.pow(0.96f) * 1.04f,
                )
                LutPreset.Mono -> {
                    val value = luminance(r, g, b)
                    floatArrayOf(value, value, value)
                }
                LutPreset.Fade -> floatArrayOf(
                    r * 0.82f + 0.10f,
                    g * 0.84f + 0.09f,
                    b * 0.86f + 0.08f,
                )
                LutPreset.Punch -> floatArrayOf(
                    ((r - 0.5f) * 1.22f + 0.5f),
                    ((g - 0.5f) * 1.22f + 0.5f),
                    ((b - 0.5f) * 1.22f + 0.5f),
                )
                LutPreset.Teal -> {
                    val shadows = (1f - luminance(r, g, b)).pow(2)
                    floatArrayOf(r - 0.035f * shadows, g + 0.025f * shadows, b + 0.055f * shadows)
                }
            }
        }

        fun generate(
            size: Int,
            transform: (red: Float, green: Float, blue: Float) -> FloatArray,
        ): CubeLut {
            require(size >= 2)
            val data = FloatArray(size * size * size * CHANNELS)
            var offset = 0
            for (blue in 0 until size) {
                for (green in 0 until size) {
                    for (red in 0 until size) {
                        val mapped = transform(
                            red / (size - 1f),
                            green / (size - 1f),
                            blue / (size - 1f),
                        )
                        require(mapped.size == CHANNELS) { "A LUT entry must contain RGB values" }
                        repeat(CHANNELS) { channel ->
                            data[offset++] = mapped[channel].coerceIn(0f, 1f)
                        }
                    }
                }
            }
            return CubeLut(size, data)
        }

        fun parse(text: String): CubeLut {
            var size: Int? = null
            val values = ArrayList<Float>()
            text.lineSequence().forEachIndexed { index, source ->
                val line = source.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                when (parts.first().uppercase()) {
                    "TITLE", "DOMAIN_MIN", "DOMAIN_MAX" -> Unit
                    "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid LUT_3D_SIZE on line ${index + 1}")
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("1D .cube LUTs are not supported")
                    else -> {
                        require(parts.size == CHANNELS) { "Invalid RGB entry on line ${index + 1}" }
                        parts.forEach { value ->
                            values += value.toFloatOrNull()
                                ?: throw IllegalArgumentException("Invalid number on line ${index + 1}")
                        }
                    }
                }
            }
            val cubeSize = requireNotNull(size) { "Missing LUT_3D_SIZE" }
            require(cubeSize in 2..65) { "LUT size must be between 2 and 65" }
            require(values.size == cubeSize * cubeSize * cubeSize * CHANNELS) {
                "Expected ${cubeSize * cubeSize * cubeSize} RGB entries"
            }
            return CubeLut(cubeSize, values.toFloatArray())
        }

        private fun luminance(red: Float, green: Float, blue: Float): Float =
            0.2126f * red + 0.7152f * green + 0.0722f * blue

        private fun mix(start: Float, end: Float, amount: Float): Float =
            start + (end - start) * amount

        private fun toByte(value: Float): Int =
            (max(0f, min(1f, value)) * 255f + 0.5f).toInt()

        private fun mixByte(start: Int, end: Int, amount: Float): Int =
            (start + (end - start) * amount).toInt().coerceIn(0, 255)
    }
}
