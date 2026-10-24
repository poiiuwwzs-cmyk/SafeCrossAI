package com.safecross.ai.detect

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.safecross.ai.risk.VehicleBox

/** On-device vehicle detection (EfficientDet-Lite0, COCO labels). No video leaves the phone. */
class VehicleDetector(context: Context) : AutoCloseable {

    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(0.4f)
            .setMaxResults(10)
            .build()
        detector = ObjectDetector.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): List<VehicleBox> {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { detection ->
            val category = detection.categories().firstOrNull() ?: return@mapNotNull null
            val name = category.categoryName()
            if (name !in VEHICLE_LABELS) return@mapNotNull null
            val box = detection.boundingBox()
            VehicleBox(
                label = name,
                left = (box.left / w).coerceIn(0f, 1f),
                top = (box.top / h).coerceIn(0f, 1f),
                right = (box.right / w).coerceIn(0f, 1f),
                bottom = (box.bottom / h).coerceIn(0f, 1f)
            )
        }
    }

    override fun close() {
        detector.close()
    }

    companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        val VEHICLE_LABELS = setOf("car", "truck", "bus", "motorcycle", "bicycle")
    }
}
