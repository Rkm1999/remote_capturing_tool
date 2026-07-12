package com.ryu.sonyremote.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.location.Location
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JpegMediaStore(private val contentResolver: ContentResolver) {
    suspend fun save(jpeg: ByteArray, prefix: String = "SONY"): Uri = withContext(Dispatchers.IO) {
        val normalizedJpeg = JpegValidator.normalize(jpeg)
        require(prefix.matches(FILE_PREFIX)) { "Invalid image filename prefix" }
        val displayName = "${prefix}_${FILE_TIME_FORMAT.format(Instant.now())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Sony Remote",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = requireNotNull(contentResolver.insert(collection, values)) {
            "Android could not create an image in MediaStore"
        }
        try {
            contentResolver.openOutputStream(item, "w").use { output ->
                requireNotNull(output) { "Android could not open the destination image" }
                output.write(normalizedJpeg)
                output.flush()
            }
            contentResolver.update(
                item,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            item
        } catch (error: Throwable) {
            contentResolver.delete(item, null, null)
            throw error
        }
    }

    suspend fun copyExif(sourceJpeg: ByteArray, destination: Uri) = withContext(Dispatchers.IO) {
        val source = ExifInterface(ByteArrayInputStream(sourceJpeg))
        contentResolver.openFileDescriptor(destination, "rw").use { descriptor ->
            val target = ExifInterface(
                requireNotNull(descriptor) { "Android could not open the saved image metadata" }.fileDescriptor,
            )
            COPIED_EXIF_TAGS.forEach { tag ->
                source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            }
            source.latLong?.let { target.setLatLong(it[0], it[1]) }
            source.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let(target::setAltitude)
            target.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL.toString(),
            )
            target.saveAttributes()
        }
    }

    suspend fun setLocation(destination: Uri, location: Location) = withContext(Dispatchers.IO) {
        contentResolver.openFileDescriptor(destination, "rw").use { descriptor ->
            val exif = ExifInterface(requireNotNull(descriptor).fileDescriptor)
            exif.setLatLong(location.latitude, location.longitude)
            if (location.hasAltitude()) exif.setAltitude(location.altitude)
            exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, location.provider ?: "PHONE")
            exif.saveAttributes()
        }
    }

    private companion object {
        val FILE_PREFIX = Regex("[A-Z0-9_]{1,32}")
        val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_COLOR_SPACE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )
        val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC)
    }
}
