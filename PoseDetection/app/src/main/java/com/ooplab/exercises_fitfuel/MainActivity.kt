package com.ooplab.exercises_fitfuel

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var skeletonOverlay: SkeletonOverlayView
    private lateinit var btnSwitchCamera: FloatingActionButton
    private lateinit var poseLandmarker: PoseLandmarker

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()

        previewView      = findViewById(R.id.previewCam)
        skeletonOverlay  = findViewById(R.id.skeletonOverlay)
        btnSwitchCamera  = findViewById(R.id.btnSwitchCamera)

        initCameraExecutor()
        requestCameraPermission()

        btnSwitchCamera.setOnClickListener { switchCamera() }
    }

    // ── Camera switching ──────────────────────────────────────────────────────

    private fun switchCamera() {
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA

        // Animate the FAB rotation for tactile feedback
        btnSwitchCamera.animate()
            .rotationBy(180f)
            .setDuration(300)
            .start()

        skeletonOverlay.clear()
        setupCamera()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializePoseLandmarker()
    }

    private fun initializePoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, inputImage ->
                // Update the skeleton overlay on every frame
                skeletonOverlay.updateLandmarks(result, inputImage.width, inputImage.height)

                // Optional: log landmarks for debugging
                var count = 0
                result.landmarks().forEach { landmark ->
                    landmark.forEach {
                        Log.d("PoseLandmarks", "landmark: $count , x=${it.x()}, y=${it.y()}, z=${it.z()}")
                        count++
                    }
                }
            }
            .setErrorListener { error ->
                Log.e("PoseLandmarker", "Error: ${error.message}")
            }
            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(this, options)
    }

    // ── Camera permission ─────────────────────────────────────────────────────

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    private fun requestCameraPermission() {
        if (hasCameraPermission()) setupCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ── Camera setup ──────────────────────────────────────────────────────────

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(cameraExecutor, ::analyzeImage) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("CameraSetup", "Error binding camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Image analysis ────────────────────────────────────────────────────────

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && imageProxy.format == ImageFormat.YUV_420_888) {
            val bitmap = yuvToRgb(mediaImage, imageProxy)
            val mpImage: MPImage

            if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                mpImage = BitmapImageBuilder(rotatedBitmap).build()
            } else {
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                mpImage = BitmapImageBuilder(rotatedBitmap).build()
            }

            poseLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
            imageProxy.close()
        } else {
            Log.e("AnalyzeImage", "Unsupported image format")
            imageProxy.close()
        }
    }

    private fun yuvToRgb(image: Image, imageProxy: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarker.close()
    }
}