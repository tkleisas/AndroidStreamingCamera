package com.streamcam.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer

data class Detection(
    val label: String,
    val classId: Int,
    val confidence: Float,
    val boundingBox: RectF,
)

class YoloDetector private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
    private val inputSize: Int,
    private val confidenceThreshold: Float,
    private val iouThreshold: Float,
) {
    companion object {
        private const val TAG = "YoloDetector"

        private val COCO_CLASSES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )

        fun create(
            context: Context,
            modelFileName: String = "yolov8n.onnx",
            inputSize: Int = 320,
            confidenceThreshold: Float = 0.4f,
            iouThreshold: Float = 0.5f,
        ): YoloDetector? {
            return try {
                val env = OrtEnvironment.getEnvironment()
                val modelBytes = context.assets.open(modelFileName).use { it.readBytes() }
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                val session = env.createSession(modelBytes, options)
                Log.i(TAG, "Model loaded — inputs: ${session.inputNames}, outputs: ${session.outputNames}")
                YoloDetector(session, env, inputSize, confidenceThreshold, iouThreshold)
            } catch (e: Exception) {
                Log.w(TAG, "YOLO model not available: ${e.message}")
                null
            }
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // NCHW format: [1, 3, H, W]
        val chw = inputSize * inputSize
        val inputData = FloatArray(3 * chw)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            inputData[i] = ((pixel shr 16) and 0xFF) / 255f
            inputData[chw + i] = ((pixel shr 8) and 0xFF) / 255f
            inputData[2 * chw + i] = (pixel and 0xFF) / 255f
        }
        if (resized !== bitmap) resized.recycle()

        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape)

        val results = session.run(mapOf("images" to inputTensor))
        @Suppress("UNCHECKED_CAST")
        val output = (results[0].value) as Array<Array<FloatArray>>
        inputTensor.close()
        results.close()

        // Output shape: [1, 84, 2100] — 84 = 4 box coords + 80 class scores
        val numValues = output[0].size
        val numDetections = output[0][0].size
        val numClasses = numValues - 4

        val detections = mutableListOf<Detection>()
        for (i in 0 until numDetections) {
            val cx = output[0][0][i]
            val cy = output[0][1][i]
            val w = output[0][2][i]
            val h = output[0][3][i]

            var maxScore = 0f
            var maxClass = 0
            for (c in 0 until numClasses) {
                val score = output[0][4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = c
                }
            }
            if (maxScore < confidenceThreshold) continue

            val x1 = (cx - w / 2).coerceIn(0f, 1f)
            val y1 = (cy - h / 2).coerceIn(0f, 1f)
            val x2 = (cx + w / 2).coerceIn(0f, 1f)
            val y2 = (cy + h / 2).coerceIn(0f, 1f)

            val label = if (maxClass < COCO_CLASSES.size) COCO_CLASSES[maxClass] else "class_$maxClass"
            detections.add(Detection(label, maxClass, maxScore, RectF(x1, y1, x2, y2)))
        }

        return nms(detections)
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<Detection>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && sorted[i].classId == sorted[j].classId &&
                    iou(sorted[i].boundingBox, sorted[j].boundingBox) > iouThreshold
                ) {
                    suppressed[j] = true
                }
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val inter = (right - left) * (bottom - top)
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    fun close() = session.close()
}
