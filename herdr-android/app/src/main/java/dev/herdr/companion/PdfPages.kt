package dev.herdr.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** Renderer boundary permits deterministic UI tests without Robolectric's missing PDF JNI. */
interface PdfPages : AutoCloseable {
    val count: Int
    fun render(index: Int, desiredWidth: Int): Bitmap
}

class AndroidPdfPages(context: Context, bytes: ByteArray) : PdfPages {
    private val source = File(context.cacheDir, "review-${java.util.UUID.randomUUID()}.pdf")
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    init {
        try {
            require(bytes.size <= ReviewDecoding.MAX_BYTES)
            source.writeBytes(bytes)
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
            require(renderer!!.pageCount > 0)
        } catch (error: Exception) { close(); throw error }
    }
    override val count: Int get() = requireNotNull(renderer).pageCount
    override fun render(index: Int, desiredWidth: Int): Bitmap = requireNotNull(renderer).openPage(index).use { page ->
        val (w, h) = ReviewDecoding.rasterSize(page.width, page.height, desiredWidth)
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
            try { bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            catch (error: Exception) { bitmap.recycle(); throw error }
        }
    }
    override fun close() {
        runCatching { renderer?.close() }; renderer = null
        runCatching { descriptor?.close() }; descriptor = null; source.delete()
    }
}
