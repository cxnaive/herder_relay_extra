package dev.herdr.companion

import kotlin.math.min

/** Pure viewport geometry; zoom is relative to the aspect-fit size, never a stretched bitmap. */
class ReviewViewport {
    var width = 0f; private set
    var height = 0f; private set
    var imageWidth = 1f; private set
    var imageHeight = 1f; private set
    var zoom = 1f; private set
    var x = 0f; private set
    var y = 0f; private set
    val fit: Float get() = min(width / imageWidth, height / imageHeight).coerceAtLeast(0.0001f)
    val scale: Float get() = fit * zoom
    fun size(w: Float, h: Float, iw: Float = imageWidth, ih: Float = imageHeight, reset: Boolean = false) {
        val cx = if (width > 0) (width / 2 - x) / (imageWidth * scale) else .5f
        val cy = if (height > 0) (height / 2 - y) / (imageHeight * scale) else .5f
        width = w.coerceAtLeast(1f); height = h.coerceAtLeast(1f); imageWidth = iw.coerceAtLeast(1f); imageHeight = ih.coerceAtLeast(1f)
        if (reset) { fitScreen(); return }
        x = width / 2 - cx * imageWidth * scale; y = height / 2 - cy * imageHeight * scale; clamp()
    }
    fun fitScreen() { zoom = 1f; x = (width - imageWidth * scale) / 2; y = (height - imageHeight * scale) / 2; clamp() }
    fun zoomTo(value: Float, focusX: Float = width / 2, focusY: Float = height / 2) {
        if (!value.isFinite() || !focusX.isFinite() || !focusY.isFinite()) return
        val next = value.coerceIn(1f, 16f); val ratio = next / zoom
        x = focusX - (focusX - x) * ratio; y = focusY - (focusY - y) * ratio; zoom = next; clamp()
    }
    fun pan(dx: Float, dy: Float) { if (dx.isFinite() && dy.isFinite()) { x += dx; y += dy; clamp() } }
    private fun clamp() {
        val w = imageWidth * scale; val h = imageHeight * scale
        x = if (w <= width) (width - w) / 2 else x.coerceIn(width - w, 0f)
        y = if (h <= height) (height - h) / 2 else y.coerceIn(height - h, 0f)
    }
}
