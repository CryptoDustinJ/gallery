/*
 * Accessibility Service for phone automation.
 *
 * Provides the SwarmActionHandler implementation that lets the HTTP server
 * tap, type, swipe, take screenshots, and control apps on the device.
 *
 * Must be enabled manually by the user in Settings > Accessibility.
 */

package com.google.ai.edge.gallery.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SwarmA11y"
private const val GESTURE_DURATION_MS = 100L
private const val SWIPE_DURATION_MS = 300L
private const val SCREENSHOT_TIMEOUT_SEC = 5L

class SwarmAccessibilityService : AccessibilityService(), SwarmActionHandler {

    companion object {
        // Singleton reference so the server can find the running service.
        @Volatile
        var instance: SwarmAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Swarm Accessibility Service connected")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Swarm Accessibility Service destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we're only here for the performGlobalAction / gesture APIs.
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // ─── SwarmActionHandler implementation ───────────────────────────────────────

    override fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeScreenshot requires API 30+")
            return null
        }

        val bitmapRef = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer,
                        result.colorSpace
                    )
                    // Convert to software bitmap for PNG encoding
                    bitmapRef.set(hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                    hardwareBitmap?.recycle()
                    result.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    latch.countDown()
                }
            }
        )

        latch.await(SCREENSHOT_TIMEOUT_SEC, TimeUnit.SECONDS)
        return bitmapRef.get()
    }

    override fun takePhoto(): Bitmap? {
        // CameraX photo capture — requires the app to have camera permission.
        // This is a simplified synchronous wrapper. In production, you'd want
        // the camera provider lifecycle managed by the activity.
        val bitmapRef = AtomicReference<Bitmap?>(null)
        val latch = CountDownLatch(1)

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            val cameraProvider = cameraProviderFuture.get(5, TimeUnit.SECONDS)

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            // Note: In a service context we can't bind to a lifecycle easily.
            // For a real implementation, use a separate CameraActivity or a
            // ProcessLifecycleOwner. For now, we return null and log guidance.
            Log.w(TAG, "Camera capture from service requires lifecycle owner. " +
                "Use /api/chat with image field from the UI instead, or " +
                "implement a CameraActivity that returns the result.")
            cameraProvider.unbindAll()
            latch.countDown()
        } catch (e: Exception) {
            Log.e(TAG, "Camera capture failed", e)
            latch.countDown()
        }

        latch.await(10, TimeUnit.SECONDS)
        return bitmapRef.get()
    }

    override fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    override fun typeText(text: String): Boolean {
        // Use the clipboard + paste approach for reliable text input
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("swarm", text)
        clipboard.setPrimaryClip(clip)

        // Find focused node and paste
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            focusedNode.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE
            )
            focusedNode.recycle()
            rootNode.recycle()
            return true
        }
        rootNode.recycle()
        return false
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSync(gesture)
    }

    override fun openApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            return true
        }
        Log.w(TAG, "No launch intent for package: $packageName")
        return false
    }

    override fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun getClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun dispatchGestureSync(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val successRef = AtomicReference(false)

        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    successRef.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    latch.countDown()
                }
            },
            null
        )

        latch.await(3, TimeUnit.SECONDS)
        return successRef.get()
    }
}
