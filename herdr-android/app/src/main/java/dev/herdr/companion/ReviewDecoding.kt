package dev.herdr.companion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.util.Base64
import kotlin.math.min
import kotlin.math.sqrt

object ReviewDecoding {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_PIXELS = 8 * 1024 * 1024
    fun bytes(doc: JSONObject): ByteArray {
        if (doc.optString("kind") == "text" && !doc.has("data")) return doc.optString("text").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
        val data = doc.optString("data").ifBlank { doc.optString("data_url").substringAfter(',') }
        require(data.length <= (MAX_BYTES / 3 + 1) * 4)
        return Base64.getDecoder().decode(data).also { require(it.size <= MAX_BYTES) }
    }
    fun image(bytes: ByteArray): Bitmap {
        require(bytes.size <= MAX_BYTES)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..65536 && options.outHeight in 1..65536)
        options.inJustDecodeBounds = false; options.inSampleSize = 1
        while (options.outWidth.toLong() * options.outHeight / options.inSampleSize / options.inSampleSize > MAX_PIXELS || maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 8192) options.inSampleSize *= 2
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
    }
    fun rasterSize(width: Int, height: Int, desiredWidth: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(desiredWidth.toDouble() / width, min(8192.0 / maxOf(width, height), sqrt(MAX_PIXELS.toDouble() / width / height)))
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }
}
