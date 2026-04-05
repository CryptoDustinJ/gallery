/*
 * Swarm Bridge Server — HTTP API for remote agent communication.
 *
 * Exposes the on-device Gemma 4 model to external systems (e.g., a Claude agent swarm)
 * via a lightweight HTTP server running on the phone.
 *
 * Endpoints:
 *   POST /api/chat          — Send text (+ optional image/audio) to Gemma, get response
 *   POST /api/action        — Execute a phone action directly (skip model)
 *   GET  /api/screen        — Capture and return a screenshot
 *   GET  /api/notifications — Return recent notifications
 *   GET  /api/status        — Health check (battery, model, connectivity)
 *   POST /api/camera        — Snap a photo with the camera, return base64
 *   POST /api/hive          — Forward data to the hive-mind ledger
 */

package com.google.ai.edge.gallery.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Base64
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.gallery.ui.llmchat.LlmModelInstance
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SwarmBridgeServer"
private const val DEFAULT_PORT = 8080
private const val INFERENCE_TIMEOUT_SEC = 120L

class SwarmBridgeServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
) : NanoHTTPD(port) {

    // The currently loaded model — set by the UI when a model is initialized.
    @Volatile var activeModel: Model? = null

    // Callback for performing phone actions (screenshot, tap, etc.)
    var actionHandler: SwarmActionHandler? = null

    // Notification reader callback
    var notificationReader: (() -> JSONArray)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "$method $uri")

        return try {
            when {
                method == Method.POST && uri == "/api/chat" -> handleChat(session)
                method == Method.POST && uri == "/api/action" -> handleAction(session)
                method == Method.GET && uri == "/api/screen" -> handleScreenshot()
                method == Method.GET && uri == "/api/notifications" -> handleNotifications()
                method == Method.GET && uri == "/api/status" -> handleStatus()
                method == Method.POST && uri == "/api/camera" -> handleCamera(session)
                method == Method.POST && uri == "/api/hive" -> handleHiveForward(session)
                method == Method.GET && uri == "/api/ping" -> jsonResponse(JSONObject().put("pong", true))
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found: $uri"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            errorResponse(500, e.message ?: "Internal error")
        }
    }

    // ─── POST /api/chat ──────────────────────────────────────────────────────────
    // Body: { "text": "...", "image": "<base64 png>", "audio": "<base64 pcm16>" }
    // All fields optional but at least one required.
    // Returns: { "response": "...", "thinking": "..." }
    private fun handleChat(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)

        val model = activeModel
            ?: return errorResponse(503, "No model loaded. Open Edge Gallery and load a model first.")

        val instance = model.instance as? LlmModelInstance
            ?: return errorResponse(503, "Model not initialized.")

        val contents = mutableListOf<Content>()

        // Image input
        if (json.has("image")) {
            val imageBytes = Base64.decode(json.getString("image"), Base64.DEFAULT)
            contents.add(Content.ImageBytes(imageBytes))
        }

        // Audio input
        if (json.has("audio")) {
            val audioBytes = Base64.decode(json.getString("audio"), Base64.DEFAULT)
            contents.add(Content.AudioBytes(audioBytes))
        }

        // Text input (added last for accurate last-token positioning)
        if (json.has("text")) {
            val text = json.getString("text").trim()
            if (text.isNotEmpty()) {
                contents.add(Content.Text(text))
            }
        }

        if (contents.isEmpty()) {
            return errorResponse(400, "Provide at least one of: text, image, audio")
        }

        // Run inference synchronously (blocking the HTTP thread is fine for NanoHTTPD)
        val responseBuf = StringBuilder()
        val thinkingBuf = StringBuilder()
        val errorRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val conversation = instance.conversation
        conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    responseBuf.append(message.toString())
                    message.channels["thought"]?.let { thinkingBuf.append(it) }
                }

                override fun onDone() {
                    latch.countDown()
                }

                override fun onError(throwable: Throwable) {
                    errorRef.set(throwable.message ?: "Inference error")
                    latch.countDown()
                }
            },
            emptyMap(),
        )

        val completed = latch.await(INFERENCE_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!completed) {
            return errorResponse(504, "Inference timed out after ${INFERENCE_TIMEOUT_SEC}s")
        }

        errorRef.get()?.let { return errorResponse(500, it) }

        val result = JSONObject()
            .put("response", responseBuf.toString())
            .put("thinking", thinkingBuf.toString())

        return jsonResponse(result)
    }

    // ─── POST /api/action ────────────────────────────────────────────────────────
    // Body: { "action": "screenshot|tap|type|open_app|clipboard|location", "params": {...} }
    private fun handleAction(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val action = json.optString("action", "")
        val params = json.optJSONObject("params") ?: JSONObject()

        val handler = actionHandler
            ?: return errorResponse(503, "Action handler not registered")

        return when (action) {
            "tap" -> {
                val x = params.getInt("x")
                val y = params.getInt("y")
                val success = handler.tap(x, y)
                jsonResponse(JSONObject().put("success", success))
            }
            "type" -> {
                val text = params.getString("text")
                val success = handler.typeText(text)
                jsonResponse(JSONObject().put("success", success))
            }
            "swipe" -> {
                val x1 = params.getInt("x1")
                val y1 = params.getInt("y1")
                val x2 = params.getInt("x2")
                val y2 = params.getInt("y2")
                val success = handler.swipe(x1, y1, x2, y2)
                jsonResponse(JSONObject().put("success", success))
            }
            "open_app" -> {
                val packageName = params.getString("package")
                val success = handler.openApp(packageName)
                jsonResponse(JSONObject().put("success", success))
            }
            "press_back" -> {
                val success = handler.pressBack()
                jsonResponse(JSONObject().put("success", success))
            }
            "press_home" -> {
                val success = handler.pressHome()
                jsonResponse(JSONObject().put("success", success))
            }
            "clipboard" -> {
                val text = handler.getClipboard()
                jsonResponse(JSONObject().put("text", text ?: ""))
            }
            else -> errorResponse(400, "Unknown action: $action")
        }
    }

    // ─── GET /api/screen ─────────────────────────────────────────────────────────
    // Returns: { "image": "<base64 png>", "width": N, "height": N }
    private fun handleScreenshot(): Response {
        val handler = actionHandler
            ?: return errorResponse(503, "Action handler not registered")

        val bitmap = handler.takeScreenshot()
            ?: return errorResponse(500, "Failed to capture screenshot")

        val base64 = bitmapToBase64(bitmap)
        val result = JSONObject()
            .put("image", base64)
            .put("width", bitmap.width)
            .put("height", bitmap.height)

        return jsonResponse(result)
    }

    // ─── GET /api/notifications ──────────────────────────────────────────────────
    // Returns: { "notifications": [...] }
    private fun handleNotifications(): Response {
        val reader = notificationReader
            ?: return errorResponse(503, "Notification listener not active. Enable in Settings > Notifications.")

        val notifs = reader()
        return jsonResponse(JSONObject().put("notifications", notifs))
    }

    // ─── GET /api/status ─────────────────────────────────────────────────────────
    private fun handleStatus(): Response {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val modelName = activeModel?.name ?: "none"
        val modelReady = activeModel?.instance != null

        val result = JSONObject()
            .put("server", "swarm-bridge")
            .put("version", "1.0.0")
            .put("device", Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
            .put("battery_pct", batteryPct)
            .put("charging", isCharging)
            .put("wifi", hasWifi)
            .put("cellular", hasCellular)
            .put("model_name", modelName)
            .put("model_ready", modelReady)

        return jsonResponse(result)
    }

    // ─── POST /api/camera ────────────────────────────────────────────────────────
    // Body: { "prompt": "what am I looking at?" }
    // Snaps a photo, sends it + prompt to Gemma, returns the response.
    private fun handleCamera(session: IHTTPSession): Response {
        val body = parseBody(session)
        val json = JSONObject(body)
        val prompt = json.optString("prompt", "Describe what you see in this image.")

        val handler = actionHandler
            ?: return errorResponse(503, "Action handler not registered")

        val photo = handler.takePhoto()
            ?: return errorResponse(500, "Failed to capture photo")

        // Feed photo + prompt to the model
        val model = activeModel
            ?: return errorResponse(503, "No model loaded")
        val instance = model.instance as? LlmModelInstance
            ?: return errorResponse(503, "Model not initialized")

        val photoBytes = bitmapToByteArray(photo)
        val contents = listOf(
            Content.ImageBytes(photoBytes),
            Content.Text(prompt),
        )

        val responseBuf = StringBuilder()
        val errorRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        instance.conversation.sendMessageAsync(
            Contents.of(contents),
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    responseBuf.append(message.toString())
                }
                override fun onDone() { latch.countDown() }
                override fun onError(throwable: Throwable) {
                    errorRef.set(throwable.message)
                    latch.countDown()
                }
            },
            emptyMap(),
        )

        latch.await(INFERENCE_TIMEOUT_SEC, TimeUnit.SECONDS)
        errorRef.get()?.let { return errorResponse(500, it) }

        val result = JSONObject()
            .put("response", responseBuf.toString())
            .put("image", bitmapToBase64(photo))

        return jsonResponse(result)
    }

    // ─── POST /api/hive ──────────────────────────────────────────────────────────
    // Forwards data to the hive-mind ledger endpoint.
    // Body: { "agent": "phone", "type": "observation", "data": "..." }
    private fun handleHiveForward(session: IHTTPSession): Response {
        val body = parseBody(session)
        // Just acknowledge — actual forwarding happens via the swarm bridge skill
        // or a background task that polls and pushes.
        val json = JSONObject(body)
        Log.i(TAG, "Hive packet: ${json.optString("type")}: ${json.optString("data")}")
        return jsonResponse(JSONObject().put("queued", true))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun parseBody(session: IHTTPSession): String {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(contentLength)
        session.inputStream.read(buf, 0, contentLength)
        return String(buf)
    }

    private fun jsonResponse(json: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            json.toString()
        )
    }

    private fun errorResponse(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            404 -> Response.Status.NOT_FOUND
            503 -> Response.Status.SERVICE_UNAVAILABLE
            504 -> Response.Status.lookup(504) ?: Response.Status.INTERNAL_ERROR
            else -> Response.Status.INTERNAL_ERROR
        }
        val json = JSONObject().put("error", message)
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}

/**
 * Interface for phone automation actions.
 * Implemented by the AccessibilityService or a MediaProjection-based handler.
 */
interface SwarmActionHandler {
    fun takeScreenshot(): Bitmap?
    fun takePhoto(): Bitmap?
    fun tap(x: Int, y: Int): Boolean
    fun typeText(text: String): Boolean
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): Boolean
    fun openApp(packageName: String): Boolean
    fun pressBack(): Boolean
    fun pressHome(): Boolean
    fun getClipboard(): String?
}
