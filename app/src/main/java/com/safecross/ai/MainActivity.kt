package com.safecross.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.safecross.ai.databinding.ActivityMainBinding
import com.safecross.ai.detect.VehicleDetector
import com.safecross.ai.risk.CameraOptics
import com.safecross.ai.risk.RiskEngine
import com.safecross.ai.risk.RiskLevel
import com.safecross.ai.risk.RiskResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private var detector: VehicleDetector? = null
    private var optics: CameraOptics? = null
    private val riskEngine = RiskEngine()
    private var lastAlertMs = 0L
    private var tone: ToneGenerator? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showMessage(getString(R.string.permission_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        analysisExecutor = Executors.newSingleThreadExecutor()
        optics = readCameraOptics()

        detector = try {
            VehicleDetector(applicationContext)
        } catch (e: Exception) {
            null
        }
        if (detector == null) {
            showMessage(getString(R.string.detector_error))
            return
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Lens data of the first back camera (the one CameraX picks by default). Null if unavailable. */
    private fun readCameraOptics(): CameraOptics? = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        if (backId == null) {
            null
        } else {
            val characteristics = manager.getCameraCharacteristics(backId)
            val focal = characteristics
                .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            if (focal == null || size == null) null else CameraOptics(focal, size.width, size.height)
        }
    } catch (e: Exception) {
        null
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val activeDetector = detector ?: return
            val rotation = image.imageInfo.rotationDegrees
            // Sensor timestamp: more accurate than "now" because detection time varies.
            val frameMs = TimeUnit.NANOSECONDS.toMillis(image.imageInfo.timestamp)
            val bitmap = rotate(image.toBitmap(), rotation)
            val boxes = activeDetector.detect(bitmap)
            val result = riskEngine.update(boxes, frameMs, optics?.calibrationFor(rotation))
            runOnUiThread { render(result) }
        } catch (e: Exception) {
            runOnUiThread { showMessage(getString(R.string.detector_error)) }
        } finally {
            image.close()
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun render(result: RiskResult) {
        val level = result.level
        val (textRes, colorRes) = when (level) {
            RiskLevel.NONE -> {
                val res = if (result.stationaryCount > 0) R.string.status_parked else R.string.status_none
                res to R.color.risk_none
            }
            RiskLevel.CAUTION -> R.string.status_caution to R.color.risk_caution
            RiskLevel.DANGER -> R.string.status_danger to R.color.risk_danger
        }

        var text = getString(textRes)
        val speed = result.focus?.speedKmh
        if (level != RiskLevel.NONE && speed != null) {
            val rounded = (speed / 5.0).roundToInt() * 5
            if (rounded >= 5) {
                text += "\n" + getString(R.string.status_speed_estimate, rounded)
            }
        }

        binding.statusText.text = text
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        if (level == RiskLevel.DANGER) alert()
    }

    private fun showMessage(message: String) {
        binding.statusText.text = message
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, R.color.risk_none))
    }

    @Suppress("DEPRECATION")
    private fun alert() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertMs < 1000) return
        lastAlertMs = now

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

        if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        try {
            analysisExecutor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        detector?.close()
        tone?.release()
    }
}
