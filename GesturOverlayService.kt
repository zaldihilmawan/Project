package com.zaldi.gesturecontrol

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode

class GestureOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "gesture_control_channel"
        const val NOTIF_ID = 1
        // Throttle gesture detection to save battery
        const val GESTURE_INTERVAL_MS = 150L
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var handLandmarker: HandLandmarker? = null
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var captureSession: CameraCaptureSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var lastGestureTime = 0L
    private var lastGesture = ""

    // Landmark indices for gesture detection
    private val WRIST = 0
    private val THUMB_TIP = 4
    private val INDEX_TIP = 8
    private val MIDDLE_TIP = 12
    private val RING_TIP = 16
    private val PINKY_TIP = 20
    private val INDEX_MCP = 5
    private val MIDDLE_MCP = 9
    private val RING_MCP = 13
    private val PINKY_MCP = 17

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initMediaPipe()
        setupOverlay()
        startCamera()
    }

    // ─── MediaPipe Init ────────────────────────────────────────────────────────
    private fun initMediaPipe() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.6f)
            .setMinHandPresenceConfidence(0.6f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> processHandResult(result) }
            .build()

        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }

    // ─── Overlay Camera Preview (small, draggable) ─────────────────────────────
    private fun setupOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_camera, null)

        val params = WindowManager.LayoutParams(
            120.dp, 120.dp,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100
        }

        // Make overlay draggable
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        overlayView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (initialTouchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
    }

    // ─── Camera2 Setup ─────────────────────────────────────────────────────────
    private fun startCamera() {
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = getFrontCameraId() ?: return

        // Low resolution to save battery
        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (now - lastGestureTime >= GESTURE_INTERVAL_MS) {
                val bitmap = image.toBitmap()
                val mpImage = BitmapImageBuilder(bitmap).build()
                handLandmarker?.detectAsync(mpImage, now)
            }
            image.close()
        }, handler)

        cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, handler)
    }

    private fun createCaptureSession() {
        val surface = imageReader!!.surface
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        // Low FPS to save battery (8 FPS is enough for gestures)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(8, 10))
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    }
                    session.setRepeatingRequest(request.build(), null, handler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, handler
        )
    }

    private fun getFrontCameraId(): String? {
        cameraManager?.cameraIdList?.forEach { id ->
            val chars = cameraManager?.getCameraCharacteristics(id)
            if (chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                return id
        }
        return null
    }

    // ─── Hand Result Processing ────────────────────────────────────────────────
    private fun processHandResult(result: HandLandmarkerResult) {
        if (result.landmarks().isEmpty()) return

        val landmarks = result.landmarks()[0]
        val gesture = detectGesture(landmarks)

        if (gesture.isNotEmpty() && gesture != lastGesture) {
            lastGesture = gesture
            lastGestureTime = SystemClock.elapsedRealtime()
            handler.post { executeGesture(gesture) }
        }
    }

    private fun detectGesture(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>): String {
        val indexUp = landmarks[INDEX_TIP].y() < landmarks[INDEX_MCP].y()
        val middleUp = landmarks[MIDDLE_TIP].y() < landmarks[MIDDLE_MCP].y()
        val ringUp = landmarks[RING_TIP].y() < landmarks[RING_MCP].y()
        val pinkyUp = landmarks[PINKY_TIP].y() < landmarks[PINKY_MCP].y()
        val thumbRight = landmarks[THUMB_TIP].x() > landmarks[INDEX_MCP].x()

        // Swipe detection via wrist movement (tracked across frames)
        val wristY = landmarks[WRIST].y()
        val wristX = landmarks[WRIST].x()

        return when {
            // ✊ Fist - all fingers down
            !indexUp && !middleUp && !ringUp && !pinkyUp -> "FIST_TAP"

            // 🖐 Open hand - all fingers up
            indexUp && middleUp && ringUp && pinkyUp -> "OPEN_HAND"

            // ✌️ Peace / Victory - index + middle up only
            indexUp && middleUp && !ringUp && !pinkyUp -> "PEACE_SCREENSHOT"

            // 👆 One finger up - index only
            indexUp && !middleUp && !ringUp && !pinkyUp -> "ONE_FINGER_SCROLL_UP"

            // 👍 Thumbs up
            thumbRight && !indexUp && !middleUp && !ringUp && !pinkyUp -> "THUMBS_LIKE"

            // 🤙 Shaka (thumb + pinky)
            pinkyUp && !indexUp && !middleUp && !ringUp -> "SHAKA_VOLUME"

            // Default swipe handled via motion tracking
            else -> ""
        }
    }

    // ─── Execute Gesture via AccessibilityService ──────────────────────────────
    private fun executeGesture(gesture: String) {
        val intent = Intent(GestureAccessibilityService.ACTION_GESTURE).apply {
            putExtra(GestureAccessibilityService.EXTRA_GESTURE, gesture)
        }
        sendBroadcast(intent)

        // Visual feedback on overlay
        overlayView?.animate()?.scaleX(1.15f)?.scaleY(1.15f)?.setDuration(100)
            ?.withEndAction { overlayView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(100) }
    }

    // ─── Notification ──────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Gesture Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gesture Control berjalan di background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GestureOverlayService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("✋ Gesture Control Aktif")
            .setContentText("Gerakkan tangan untuk mengontrol layar")
            .setSmallIcon(R.drawable.ic_gesture)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        handLandmarker?.close()
        overlayView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?) = null

    // ─── Extensions ───────────────────────────────────────────────────────────
    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}

// Extension: Convert YUV Image to Bitmap
fun android.media.Image.toBitmap(): Bitmap {
    val planes = planes
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)
    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val out = java.io.ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
    val bytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
