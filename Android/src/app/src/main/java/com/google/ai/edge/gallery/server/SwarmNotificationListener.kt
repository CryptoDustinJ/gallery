/*
 * Notification Listener Service for the Swarm Bridge.
 *
 * Captures notifications and makes them available via the /api/notifications endpoint.
 * Must be enabled by the user in Settings > Notifications > Notification access.
 */

package com.google.ai.edge.gallery.server

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

private const val TAG = "SwarmNotifListener"
private const val MAX_NOTIFICATIONS = 50

class SwarmNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: SwarmNotificationListener? = null
            private set

        /**
         * Returns recent notifications as a JSONArray.
         * Called by the SwarmBridgeServer via the notificationReader callback.
         */
        fun getRecentNotifications(): JSONArray {
            val service = instance ?: return JSONArray()
            return try {
                val active = service.activeNotifications ?: emptyArray()
                val arr = JSONArray()
                for (sbn in active.take(MAX_NOTIFICATIONS)) {
                    val extras = sbn.notification.extras
                    val obj = JSONObject()
                        .put("package", sbn.packageName)
                        .put("title", extras.getCharSequence("android.title")?.toString() ?: "")
                        .put("text", extras.getCharSequence("android.text")?.toString() ?: "")
                        .put("time", sbn.postTime)
                        .put("key", sbn.key)
                        .put("ongoing", sbn.isOngoing)
                    arr.put(obj)
                }
                arr
            } catch (e: Exception) {
                Log.e(TAG, "Error reading notifications", e)
                JSONArray()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "Swarm Notification Listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.i(TAG, "Swarm Notification Listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Log.d(TAG, "Notification from ${sbn.packageName}: ${sbn.notification.extras.getCharSequence("android.title")}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op — we read active notifications on demand.
    }
}
