/*
 * Swarm Bridge Tools — LiteRT-LM tool definitions for on-device agent capabilities.
 *
 * These @Tool-annotated functions are registered with the Gemma model's conversation,
 * allowing Gemma to call them via function calling. When the swarm sends a prompt
 * via /api/chat, Gemma can decide to invoke these tools as part of its response.
 *
 * This gives Gemma agentic capabilities on the phone — it can read notifications,
 * take screenshots, control apps, and report back to the swarm.
 */

package com.google.ai.edge.gallery.customtasks.swarmbridge

import android.util.Log
import com.google.ai.edge.gallery.server.SwarmAccessibilityService
import com.google.ai.edge.gallery.server.SwarmNotificationListener
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SwarmBridgeTools"

class SwarmBridgeTools(
    private val hiveEndpoint: String = "http://127.0.0.1:19000",
) : ToolSet {

    /** Reads current notifications on the device. */
    @Tool(description = "Read all current notifications on the phone. Returns a list of notification titles, text, and source apps.")
    fun readNotifications(): Map<String, String> {
        Log.d(TAG, "readNotifications called")
        val notifs = SwarmNotificationListener.getRecentNotifications()
        return mapOf("result" to notifs.toString())
    }

    /** Opens an app by its package name. */
    @Tool(description = "Open an app on the phone by its package name (e.g., com.discord for Discord).")
    fun openApp(
        @ToolParam(description = "The Android package name of the app to open") packageName: String
    ): Map<String, String> {
        Log.d(TAG, "openApp called: $packageName")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val success = service.openApp(packageName)
        return mapOf("result" to if (success) "opened" else "failed")
    }

    /** Taps at screen coordinates. */
    @Tool(description = "Tap on the screen at the given x,y coordinates.")
    fun tapScreen(
        @ToolParam(description = "The x coordinate to tap") x: String,
        @ToolParam(description = "The y coordinate to tap") y: String,
    ): Map<String, String> {
        Log.d(TAG, "tapScreen called: ($x, $y)")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val success = service.tap(x.toInt(), y.toInt())
        return mapOf("result" to if (success) "tapped" else "failed")
    }

    /** Types text into the currently focused input field. */
    @Tool(description = "Type text into the currently focused text field on the screen.")
    fun typeText(
        @ToolParam(description = "The text to type") text: String
    ): Map<String, String> {
        Log.d(TAG, "typeText called: $text")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val success = service.typeText(text)
        return mapOf("result" to if (success) "typed" else "failed")
    }

    /** Swipes between two points on screen. */
    @Tool(description = "Swipe on the screen from (x1,y1) to (x2,y2). Useful for scrolling.")
    fun swipeScreen(
        @ToolParam(description = "Starting x coordinate") x1: String,
        @ToolParam(description = "Starting y coordinate") y1: String,
        @ToolParam(description = "Ending x coordinate") x2: String,
        @ToolParam(description = "Ending y coordinate") y2: String,
    ): Map<String, String> {
        Log.d(TAG, "swipeScreen called: ($x1,$y1) -> ($x2,$y2)")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val success = service.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
        return mapOf("result" to if (success) "swiped" else "failed")
    }

    /** Presses the back button. */
    @Tool(description = "Press the Android back button.")
    fun pressBack(): Map<String, String> {
        Log.d(TAG, "pressBack called")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val success = service.pressBack()
        return mapOf("result" to if (success) "done" else "failed")
    }

    /** Gets clipboard text. */
    @Tool(description = "Read the current text in the phone's clipboard.")
    fun readClipboard(): Map<String, String> {
        Log.d(TAG, "readClipboard called")
        val service = SwarmAccessibilityService.instance
            ?: return mapOf("error" to "Accessibility service not enabled")
        val text = service.getClipboard()
        return mapOf("result" to (text ?: "clipboard empty"))
    }

    /** Reports an observation to the hive-mind ledger. */
    @Tool(description = "Send an observation or finding to the agent swarm's hive-mind ledger. Use this to report important information that other agents should know about.")
    fun reportToHive(
        @ToolParam(description = "Type of report: observation, alert, or research") type: String,
        @ToolParam(description = "The information to report") data: String,
    ): Map<String, String> {
        Log.d(TAG, "reportToHive called: [$type] $data")
        return try {
            val url = URL("$hiveEndpoint/office/log-memory-packet")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val payload = JSONObject()
                .put("agent", "phone-gemma")
                .put("type", type)
                .put("data", data)
                .toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }

            val responseCode = conn.responseCode
            conn.disconnect()

            if (responseCode in 200..299) {
                mapOf("result" to "reported to hive-mind")
            } else {
                mapOf("result" to "hive returned $responseCode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reach hive-mind", e)
            mapOf("result" to "hive unreachable: ${e.message}")
        }
    }
}
