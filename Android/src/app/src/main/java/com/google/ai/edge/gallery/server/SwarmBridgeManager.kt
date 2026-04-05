/*
 * SwarmBridgeManager — Singleton that coordinates the server, accessibility service,
 * and notification listener. Provides a clean API for the rest of the app.
 *
 * Usage from UI:
 *   SwarmBridgeManager.startServer(context)
 *   SwarmBridgeManager.setActiveModel(model)
 *   SwarmBridgeManager.stopServer(context)
 */

package com.google.ai.edge.gallery.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.google.ai.edge.gallery.data.Model
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SwarmBridgeManager"

object SwarmBridgeManager {
    private var server: SwarmBridgeServer? = null
    private var boundService: SwarmBridgeService? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(8080)
    val port = _port.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? SwarmBridgeService.LocalBinder
            boundService = localBinder?.getServer()?.let { srv ->
                server = srv
                wireActionHandler()
                wireNotificationReader()
                _isRunning.value = true
                Log.i(TAG, "Bound to SwarmBridgeService, server connected")
                null // we don't actually need a reference to the service itself
            }
            server = (binder as? SwarmBridgeService.LocalBinder)?.getServer()
            wireActionHandler()
            wireNotificationReader()
            _isRunning.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            server = null
            _isRunning.value = false
            Log.i(TAG, "Disconnected from SwarmBridgeService")
        }
    }

    /**
     * Start the HTTP server as a foreground service.
     */
    fun startServer(context: Context, port: Int = 8080) {
        _port.value = port
        SwarmBridgeService.start(context, port)

        // Bind to get a reference to the server instance
        val intent = Intent(context, SwarmBridgeService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Stop the HTTP server.
     */
    fun stopServer(context: Context) {
        try {
            context.unbindService(serviceConnection)
        } catch (_: Exception) {}
        SwarmBridgeService.stop(context)
        server = null
        _isRunning.value = false
    }

    /**
     * Set the active model on the server so /api/chat knows which model to use.
     * Call this whenever a model finishes initializing.
     */
    fun setActiveModel(model: Model?) {
        server?.activeModel = model
        Log.d(TAG, "Active model set to: ${model?.name ?: "none"}")
    }

    /**
     * Get the current server instance (for direct access from within the app).
     */
    fun getServer(): SwarmBridgeServer? = server

    private fun wireActionHandler() {
        val a11y = SwarmAccessibilityService.instance
        if (a11y != null) {
            server?.actionHandler = a11y
            Log.d(TAG, "Action handler wired to accessibility service")
        } else {
            Log.w(TAG, "Accessibility service not yet available — actions will 503")
        }
    }

    private fun wireNotificationReader() {
        server?.notificationReader = { SwarmNotificationListener.getRecentNotifications() }
        Log.d(TAG, "Notification reader wired")
    }

    /**
     * Re-wire services (call after accessibility/notification services connect).
     */
    fun refreshConnections() {
        wireActionHandler()
        wireNotificationReader()
    }
}
