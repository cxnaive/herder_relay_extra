package dev.herdr.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QrScannerActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var zoom: SeekBar
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var recognition: QrRecognition? = null
    @Volatile private var completed = false
    @Volatile private var importing = false
    private var torch = false
    private var lastFrame = 0L
    private var recognitionErrors = 0
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = "相机权限未开启。可从图片识别，或返回后粘贴配对链接。"
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 27, 34)) }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        toolbar.addView(button("‹ 返回") { finish() })
        toolbar.addView(TextView(this).apply { text = "扫描配对二维码"; textSize = 19f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("手动输入") { setResult(RESULT_OK, Intent().putExtra(EXTRA_MANUAL, true)); finish() })
        root.addView(toolbar)
        status = TextView(this).apply {
            text = "将完整二维码放入画面，保留四周空白。支持深色终端二维码。"
            textSize = 14f; setTextColor(Color.rgb(180, 202, 210)); setPadding(dp(20), dp(8), dp(20), dp(12))
        }; root.addView(status)
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER }
        preview.contentDescription = "二维码相机画面，点击可以对焦"
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        preview.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = preview.meteringPointFactory.createPoint(event.x, event.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                preview.performClick()
            }
            true
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(8)) }
        controls.addView(TextView(this).apply { text = "缩放"; setTextColor(Color.WHITE) })
        zoom = SeekBar(this).apply { max = 100; contentDescription = "相机缩放"; isEnabled = false }
        zoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) camera?.cameraControl?.setLinearZoom(value / 100f) }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        controls.addView(zoom, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(button("补光") { torch = !torch; camera?.cameraControl?.enableTorch(torch) })
        root.addView(controls)
        root.addView(button("从图片识别二维码") { gallery.launch("image/*") }, LinearLayout.LayoutParams(-1, dp(56)))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { finish() } })
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                provider = future.get()
                val cameraPreview = Preview.Builder().build().apply { surfaceProvider = preview.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build()
                camera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, analysis)
                val maxZoom = (camera!!.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f).coerceAtMost(4f)
                recognition = QrRecognition(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .setZoomSuggestionOptions(ZoomSuggestionOptions.Builder { ratio ->
                        if (completed || importing || isFinishing) false else { camera?.cameraControl?.setZoomRatio(ratio.coerceIn(1f, maxZoom)); true }
                    }.setMaxSupportedZoomRatio(maxZoom).build()).build())
                camera!!.cameraInfo.zoomState.observe(this) { state -> zoom.isEnabled = state.maxZoomRatio > 1f; zoom.progress = (state.linearZoom * 100).toInt() }
                analysis.setAnalyzer(worker, ::analyze)
            } catch (_: Exception) { status.text = "相机无法启动。可从图片识别，或手动输入配对链接。" }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(frame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = frame.image
        val model = recognition
        if (completed || importing || isFinishing || media == null || model == null || now - lastFrame < 180) { frame.close(); return }
        lastFrame = now
        var inverse: Bitmap? = null
        model.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees)) {
            // Normalize luminance only; ML Kit still performs all QR detection and decoding.
            inverse = invertedLuminance(frame)
            InputImage.fromBitmap(inverse!!, frame.imageInfo.rotationDegrees)
        }.addOnSuccessListener { codes ->
            if (!importing) accept(codes)
            recognitionErrors = 0
        }.addOnFailureListener {
            recognitionErrors++
            if (recognitionErrors == 3) status.text = "识别暂时失败，可尝试从图片识别或手动输入。"
        }.addOnCompleteListener { inverse?.recycle(); frame.close() }
    }

    private fun invertedLuminance(frame: ImageProxy): Bitmap {
        val plane = frame.planes[0]; val buffer = plane.buffer.duplicate(); val start = buffer.position()
        val pixels = IntArray(frame.width * frame.height)
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val value = 255 - (buffer.get(start + y * plane.rowStride + x * plane.pixelStride).toInt() and 255)
            pixels[y * frame.width + x] = Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }

    private fun readImage(uri: Uri) {
        importing = true; status.text = "正在本机识别图片…"
        worker.execute {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = (maxOf(info.size.width, info.size.height) / 2400f).coerceAtLeast(1f)
                    decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) { bitmap?.recycle(); importing = false; return@runOnUiThread }
                if (bitmap == null) { importing = false; status.text = "无法打开这张图片，请换一张截图。"; return@runOnUiThread }
                var inverse: Bitmap? = null
                val model = QrRecognition()
                model.process(InputImage.fromBitmap(bitmap, 0)) { inverse = QrRecognition.invert(bitmap); InputImage.fromBitmap(inverse!!, 0) }
                    .addOnSuccessListener { codes -> if (!accept(codes)) status.text = "未找到完整二维码。请保留二维码四周空白，或粘贴链接。" }
                    .addOnFailureListener { status.text = "图片识别失败，请重试或粘贴链接。" }
                    .addOnCompleteListener { model.close(); bitmap.recycle(); inverse?.recycle(); importing = false }
            }
        }
    }

    private fun accept(codes: List<Barcode>): Boolean {
        if (completed || isFinishing || isDestroyed) return false
        val values = codes.mapNotNull { it.rawValue }.distinct()
        val link = values.firstOrNull { value ->
            runCatching { val uri = Uri.parse(value); uri.scheme in listOf("https", "herdr-tcp") && !uri.host.isNullOrBlank() && uri.fragment.orEmpty().contains("setup=") }.getOrDefault(false)
        }
        if (link == null) { if (values.isNotEmpty()) status.text = "已识别到二维码，但它不是 Herdr 配对链接。"; return false }
        completed = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CONTENT, link)); finish(); return true
    }

    override fun onDestroy() { provider?.unbindAll(); recognition?.close(); worker.shutdown(); super.onDestroy() }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; minimumHeight = dp(48); setOnClickListener { action() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_CONTENT = "qr_content"; const val EXTRA_MANUAL = "manual_pairing" }
}
