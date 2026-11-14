package com.safecross.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.safecross.ai.camera.FrameConverter
import com.safecross.ai.databinding.ActivityMainBinding
import com.safecross.ai.detect.VehicleDetector
import com.safecross.ai.risk.CameraOptics
import com.safecross.ai.risk.PhoneSteadiness
import com.safecross.ai.risk.RiskEngine
import com.safecross.ai.risk.RiskLevel
import com.safecross.ai.risk.RiskResult
import com.safecross.ai.sensor.PhoneMotionMonitor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var analysisExecutor: ExecutorService
    private var detector: VehicleDetector? = null

    /** Lens data of the camera CameraX actually opened. Written on main, read on analysis thread. */
    @Volatile
    private var optics: CameraOptics? = null

    // The following are only touched on the single analysis thread.
    private val riskEngine = RiskEngine()
    private val frameConverter = FrameConverter()
    private var consecutiveFailures = 0

    private val steadiness = PhoneSteadiness()
    private var motionMonitor: PhoneMotionMonitor? = null

    private var lastAlertMs = 0L
    private var tone: ToneGenerator? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else showMessage(getString(R.string.permission_needed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws apps edge-to-edge; do the same on older versions and
        // keep the texts clear of the status and navigation bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        analysisExecutor = Executors.newSingleThreadExecutor()
        motionMonitor = PhoneMotionMonitor(applicationContext, steadiness)

        detector = try {
            VehicleDetector(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the detection model", e)
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

    override fun onResume() {
        super.onResume()
        motionMonitor?.start()
    }

    override fun onPause() {
        motionMonitor?.stop()
        super.onPause()
    }

    private fun applySystemBarInsets() {
        val statusTop = binding.statusText.paddingTop
        val disclaimerBottom = binding.disclaimerText.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.statusText.updatePadding(top = statusTop + bars.top)
            binding.disclaimerText.updatePadding(bottom = disclaimerBottom + bars.bottom)
            insets
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
            val camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            optics = readCameraOptics(camera)
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Lens data of the camera that was really opened. Null if anything is missing;
     * then no speed is ever shown (only time-to-contact, which needs no calibration).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun readCameraOptics(camera: Camera): CameraOptics? = try {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val focals = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixelArray = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (focals == null || focals.isEmpty() || physical == null) {
            null
        } else {
            val widthFraction =
                if (pixelArray != null && active != null && pixelArray.width > 0) {
                    active.width().toFloat() / pixelArray.width
                } else {
                    1f
                }
            val heightFraction =
                if (pixelArray != null && active != null && pixelArray.height > 0) {
                    active.height().toFloat() / pixelArray.height
                } else {
                    1f
                }
            CameraOptics(
                focalLengthMm = focals[0],
                sensorWidthMm = physical.width,
                sensorHeightMm = physical.height,
                activeWidthFraction = widthFraction,
                activeHeightFraction = heightFraction,
                singleFocalLength = focals.size == 1
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read camera optics; speed estimate disabled", e)
        null
    }

    private fun analyze(image: ImageProxy) {
        try {
            val activeDetector = detector ?: return
            val rotation = image.imageInfo.rotationDegrees
            // Sensor timestamp: more accurate than "now" because detection time varies.
            val frameMs = TimeUnit.NANOSECONDS.toMillis(image.imageInfo.timestamp)
            val calibration = optics?.calibrationFor(rotation, image.width, image.height)
            val bitmap = frameConverter.toUprightBitmap(image)
            val boxes = activeDetector.detect(bitmap)
            val steady = steadiness.isSteady(SystemClock.elapsedRealtime())
            val result = riskEngine.update(boxes, frameMs, calibration, steady)
            consecutiveFailures = 0
            runOnUiThread { render(result) }
        } catch (e: Exception) {
            consecutiveFailures++
            Log.w(TAG, "Frame analysis failed ($consecutiveFailures in a row)", e)
            // One bad frame is normal; only report a real, lasting problem.
            if (consecutiveFailures >= FAILURES_BEFORE_ERROR) {
                riskEngine.reset()
                runOnUiThread { showMessage(getString(R.string.detector_error)) }
            }
        } finally {
            image.close()
        }
    }

    private fun render(result: RiskResult) {
        val (textRes, colorRes) = when (result.level) {
            RiskLevel.NONE -> noneText(result) to R.color.risk_none
            RiskLevel.CAUTION -> {
                val res = if (result.phoneMoving) R.string.status_caution_unsteady else R.string.status_caution
                res to R.color.risk_caution
            }
            RiskLevel.DANGER -> R.string.status_danger to R.color.risk_danger
        }

        var text = getString(textRes)
        // Speed is only set by the engine after a valid camera calibration.
        val speed = result.focus?.speedKmh
        if (result.level != RiskLevel.NONE && speed != null) {
            val rounded = (speed / 5.0).roundToInt() * 5
            if (rounded >= 5) {
                text += "\n" + getString(R.string.status_speed_estimate, rounded)
            }
        }

        binding.statusText.text = text
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        if (result.level == RiskLevel.DANGER) alert()
    }

    private fun noneText(result: RiskResult): Int = when {
        result.phoneMoving -> R.string.status_hold_steady
        result.vehicleCount == 0 -> R.string.status_none
        result.stationaryCount > 0 -> R.string.status_parked
        else -> R.string.status_not_approaching
    }

    private fun showMessage(message: String) {
        binding.statusText.text = message
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, R.color.risk_none))
    }

    private fun alert() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlertMs < 1000) return
        lastAlertMs = now

        vibrator()?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))

        if (tone == null) {
            tone = try {
                ToneGenerator(AudioManager.STREAM_ALARM, 80)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Alarm tone unavailable", e)
                null
            }
        }
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            legacyVibrator()
        }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator? = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

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

    private companion object {
        const val TAG = "SafeCross"
        const val FAILURES_BEFORE_ERROR = 5
    }
}
