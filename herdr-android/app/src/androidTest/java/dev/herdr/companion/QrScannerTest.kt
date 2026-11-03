package dev.herdr.companion

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Matrix
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class QrScannerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val link = "https://phone.example/#setup=0123456789abcdef0123456789abcdef&label=Test&gateways=wss%3A%2F%2Fgateway-one.example%2Cwss%3A%2F%2Fgateway-two.example"

    private fun qr(value: String, inverse: Boolean): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 720, 720,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(720 * 720) { index ->
            if (matrix[index % 720, index / 720] != inverse) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, 720, 720, Bitmap.Config.ARGB_8888)
    }

    private fun recognize(bitmap: Bitmap): List<String?> {
        val model = QrRecognition(); var inverted: Bitmap? = null
        return try {
            Tasks.await(model.process(InputImage.fromBitmap(bitmap, 0)) {
                inverted = QrRecognition.invert(bitmap); InputImage.fromBitmap(inverted!!, 0)
            }, 30, TimeUnit.SECONDS).map { it.rawValue }
        } finally { inverted?.recycle(); model.close() }
    }

    @Test fun bundledModelReadsNormalInvertedRotatedAndDenseScreenshotCodes() {
        for (inverse in listOf(false, true)) {
            val code = qr(link, inverse)
            assertTrue("ML Kit did not read QR inverse=$inverse", recognize(code).contains(link))
            val rotated = Bitmap.createBitmap(code, 0, 0, code.width, code.height, Matrix().apply { postRotate(90f) }, true)
            assertTrue("ML Kit did not read rotated QR", recognize(rotated).contains(link))
            if (rotated !== code) rotated.recycle(); code.recycle()
        }
        val denseLink = link + "&invite=abcdefghijklmnopqrstuv&invite_version=1&invite_expires=9999999999999&rendezvous=" + "x".repeat(43) + "&relay_id=" + "r".repeat(22)
        val dense = qr(denseLink, true)
        val screen = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        Canvas(screen).apply {
            drawColor(Color.rgb(24, 24, 28)); drawText("Herdr / Pair device", 80f, 180f, Paint().apply { textSize = 40f; color = Color.WHITE })
            drawBitmap(dense, 180f, 480f, null)
        }
        assertTrue("ML Kit did not read dense terminal screenshot", recognize(screen).contains(denseLink))
        dense.recycle(); screen.recycle()
    }

    @Test fun scannerHasVisibleBackAndManualInputAndHonorsSystemBack() {
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        ActivityScenario.launch<QrScannerActivity>(Intent(context, QrScannerActivity::class.java)).use { scenario ->
            val back = device.wait(Until.findObject(By.text("‹ 返回")), 10000)
            assertNotNull(back)
            assertNotNull(device.findObject(By.text("手动输入")))
            assertNotNull(device.findObject(By.text("从图片识别二维码")))
            var streaming = false
            var frameTime = 0L
            val cameraDeadline = System.currentTimeMillis() + 20000
            while (!streaming && System.currentTimeMillis() < cameraDeadline) {
                scenario.onActivity { activity ->
                    val preview = QrScannerActivity::class.java.getDeclaredField("preview").apply { isAccessible = true }.get(activity) as androidx.camera.view.PreviewView
                    frameTime = QrScannerActivity::class.java.getDeclaredField("lastFrame").apply { isAccessible = true }.getLong(activity)
                    streaming = preview.previewStreamState.value == androidx.camera.view.PreviewView.StreamState.STREAMING && frameTime > 0
                }
                if (!streaming) Thread.sleep(100)
            }
            assertTrue("CameraX preview/analyzer did not start", streaming)
            val firstFrame = frameTime
            Thread.sleep(1500)
            scenario.onActivity { activity -> frameTime = QrScannerActivity::class.java.getDeclaredField("lastFrame").apply { isAccessible = true }.getLong(activity) }
            assertTrue("Camera image was not released after recognition", frameTime > firstFrame)
            val screenshot = java.io.File(context.getExternalFilesDir(null), "qa/scanner.png").apply { parentFile!!.mkdirs() }
            device.waitForIdle(); device.takeScreenshot(screenshot)
            back.click()
            assertTrue(device.wait(Until.gone(By.text("扫描配对二维码")), 5000))
        }
        ActivityScenario.launch<QrScannerActivity>(Intent(context, QrScannerActivity::class.java)).use {
            assertNotNull(device.wait(Until.findObject(By.text("扫描配对二维码")), 10000))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("扫描配对二维码")), 5000))
        }
    }
}
