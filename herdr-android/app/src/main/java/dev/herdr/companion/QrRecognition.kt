package dev.herdr.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/** All detection/decoding is delegated to the bundled ML Kit model. */
class QrRecognition(options: BarcodeScannerOptions = BarcodeScannerOptions.Builder()
    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) : AutoCloseable {
    private val scanner = BarcodeScanning.getClient(options)

    fun process(image: InputImage, inverted: () -> InputImage): Task<List<Barcode>> =
        scanner.process(image).onSuccessTask { codes ->
            if (codes.any { !it.rawValue.isNullOrBlank() }) Tasks.forResult(codes)
            else scanner.process(inverted())
        }

    override fun close() = scanner.close()

    companion object {
        /** Dark terminal QR codes need polarity normalization before model inference. */
        fun invert(bitmap: Bitmap): Bitmap {
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val filter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
            Canvas(result).drawBitmap(bitmap, 0f, 0f, Paint().apply { colorFilter = filter })
            return result
        }
    }
}
