/*
 * Foreground service that keeps the Swarm Bridge HTTP server alive
 * even when the app is backgrounded or the screen is off.
 *
 * Started from GalleryApplication or toggled from the UI.
 * Runs on a configurable port (default 8080).
 */

package com.google.ai.edge.gallery.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R

private const val TAG = "SwarmBridgeService"
private const val CHANNEL_ID = "swarm_bridge_channel"
private const val NOTIFICATION_ID = 19001
private const val DEFAULT_PORT = 8080

class SwarmBridgeService : Service() {

    private var server: SwarmBridgeServer? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getServer(): SwarmBridgeServer? = server
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        if (server?.isAlive == true) {
            Log.d(TAG, "Server already running on port ${server?.listeningPort}")
            return START_STICKY
        }

        // Start the HTTP server
        server = SwarmBridgeServer(applicationContext, port).also {
            try {
                it.start()
                Log.i(TAG, "Swarm Bridge server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server on port $port", e)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Promote to foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(port))

        return START_STICKY
    }

    override fun onDestroy() {
        server?.let {
            it.stop()
            Log.i(TAG, "Swarm Bridge server stopped")
        }
        server = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Swarm Bridge",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the Swarm Bridge HTTP server running for agent communication"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(port: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SwarmBridgeService::class.java).apply {
            action = "STOP"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Swarm Bridge Active")
            .setContentText("Serving on port $port")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, SwarmBridgeService::class.java).apply {
                putExtra("port", port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SwarmBridgeService::class.java))
        }
    }
}
