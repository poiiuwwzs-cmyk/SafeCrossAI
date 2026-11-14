package com.safecross.ai.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy

/**
 * Turns an RGBA_8888 camera frame into an upright bitmap, reusing the same two
 * bitmaps for every frame instead of allocating new ones (less memory churn and
 * battery use). Not thread-safe: call it from the single analysis thread only.
 */
class FrameConverter {

    private var raw: Bitmap? = null
    private var upright: Bitmap? = null
    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun toUprightBitmap(image: ImageProxy): Bitmap {
        val source = copyToBitmap(image)
        val degrees = image.imageInfo.rotationDegrees
        if (degrees % 360 == 0) return source

        val w = source.width
        val h = source.height
        val (outW, outH) = if (degrees % 180 == 0) w to h else h to w
        val out = reuse(upright, outW, outH).also { upright = it }

        matrix.setRotate(degrees.toFloat())
        when ((degrees % 360 + 360) % 360) {
            90 -> matrix.postTranslate(h.toFloat(), 0f)
            180 -> matrix.postTranslate(w.toFloat(), h.toFloat())
            270 -> matrix.postTranslate(0f, w.toFloat())
        }
        Canvas(out).drawBitmap(source, matrix, paint)
        return out
    }

    private fun copyToBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes.firstOrNull()
        val w = image.width
        val h = image.height
        // Fast path: tightly packed RGBA rows can be copied straight in.
        if (plane != null && plane.pixelStride == 4 && plane.rowStride == w * 4) {
            val bitmap = reuse(raw, w, h).also { raw = it }
            val buffer = plane.buffer
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }
        return image.toBitmap()
    }

    private fun reuse(current: Bitmap?, w: Int, h: Int): Bitmap =
        if (current != null && current.width == w && current.height == h && !current.isRecycled) {
            current
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
}
