package dev.herdr.companion

import org.junit.Assert.*
import org.junit.Test

class ReviewViewportTest {
    @Test fun fitsWithoutStretchingAndZoomsAtTheFingerPosition() {
        val v = ReviewViewport(); v.size(400f, 800f, 1200f, 600f, true)
        assertEquals(1f / 3, v.scale, .001f); assertEquals(300f, v.y, .01f)
        val contentX = (120 - v.x) / v.scale; val contentY = (400 - v.y) / v.scale
        v.zoomTo(4f, 120f, 400f)
        assertEquals(4f, v.zoom, .001f)
        assertEquals(contentX, (120 - v.x) / v.scale, .01f); assertEquals(contentY, (400 - v.y) / v.scale, .01f)
    }
    @Test fun panCannotLoseTheImageAndResetRestoresFit() {
        val v = ReviewViewport(); v.size(400f, 600f, 800f, 1200f, true); v.zoomTo(8f)
        v.pan(100000f, -100000f)
        assertEquals(0f, v.x, 0f); assertEquals(600f - 1200f * v.scale, v.y, .01f)
        v.zoomTo(999f); assertEquals(16f, v.zoom, 0f)
        v.fitScreen(); assertEquals(1f, v.zoom, 0f); assertEquals(0f, v.x, .01f); assertEquals(0f, v.y, .01f)
    }
    @Test fun higherResolutionReplacementPreservesZoomAndViewportCenter() {
        val v = ReviewViewport(); v.size(400f, 800f, 800f, 1200f, true); v.zoomTo(5f); v.pan(-70f, 30f)
        val nx = (200 - v.x) / (v.imageWidth * v.scale); val ny = (400 - v.y) / (v.imageHeight * v.scale)
        v.size(400f, 800f, 2400f, 3600f)
        assertEquals(5f, v.zoom, 0f); assertEquals(nx, (200 - v.x) / (v.imageWidth * v.scale), .0001f); assertEquals(ny, (400 - v.y) / (v.imageHeight * v.scale), .0001f)
        v.zoomTo(Float.NaN); assertEquals(5f, v.zoom, 0f)
    }
    @Test fun pdfRasterBudgetPreservesPageAspectRatio() {
        for ((w, h) in listOf(595 to 842, 1200 to 300, 1 to 100000)) {
            val (rw, rh) = ReviewDecoding.rasterSize(w, h, 16000)
            assertTrue(rw.toLong() * rh <= ReviewDecoding.MAX_PIXELS)
            assertTrue(rw <= 8192 && rh <= 8192)
            if (w > 1) assertEquals(w.toDouble() / h, rw.toDouble() / rh, .002)
        }
    }
}
