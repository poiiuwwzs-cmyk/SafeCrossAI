package com.safecross.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import com.safecross.ai.risk.RiskEngine
import com.safecross.ai.risk.RiskLevel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private var detector: VehicleDetector? = null
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
            val bitmap = rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
            val level = riskEngine.update(activeDetector.detect(bitmap))
            runOnUiThread { render(level) }
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

    private fun render(level: RiskLevel) {
        val (textRes, colorRes) = when (level) {
            RiskLevel.NONE -> R.string.status_none to R.color.risk_none
            RiskLevel.CAUTION -> R.string.status_caution to R.color.risk_caution
            RiskLevel.DANGER -> R.string.status_danger to R.color.risk_danger
        }
        binding.statusText.setText(textRes)
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
