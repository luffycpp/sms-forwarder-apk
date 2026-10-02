package cyou.famflow.smsforwarder

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ForwarderService : Service() {

    private val CHANNEL_ID = "famflow_sms_channel"
    private val NOTIFICATION_ID = 1001
    private val TAG = "ForwarderService"
    private val HEARTBEAT_INTERVAL_MS = 20_000L  // 20 seconds

    private val prefs by lazy { getSharedPreferences("famflow_sms", MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            val notification = buildNotification("Listening for Airtel Bank SMS...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
        }

        // Start heartbeat loop immediately
        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Heartbeat ─────────────────────────────────────────────────────────────
    private fun sendHeartbeat() {
        val apiKey = prefs.getString("api_key", null) ?: return
        val webhookUrl = prefs.getString("webhook_url", null) ?: return
        val serverUrl = webhookUrl.substringBefore("/api/sms/airtel")

        Thread {
            try {
                val deviceId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                )
                val json = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("appVersion", "1.0")
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$serverUrl/api/app/heartbeat")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    val data = JSONObject(responseBody)

                    // Update last heartbeat timestamp
                    prefs.edit().putLong("last_heartbeat_ms", System.currentTimeMillis()).apply()

                    // Parse pending orders — store for quick amount matching
                    val pendingOrders = data.optJSONArray("pendingOrders")
                    val pendingCount = pendingOrders?.length() ?: 0
                    if (pendingOrders != null) {
                        val amounts = mutableListOf<String>()
                        for (i in 0 until pendingOrders.length()) {
                            val order = pendingOrders.getJSONObject(i)
                            amounts.add(order.optString("amount"))
                        }
                        prefs.edit().putString("pending_amounts", amounts.joinToString(",")).apply()
                    }

                    // Update notification
                    val notifText = if (pendingCount > 0)
                        "🔔 $pendingCount pending order(s) — watching for SMS"
                    else
                        "Listening for Airtel Bank SMS..."
                    updateNotification(notifText)

                    // If there are pending orders, also scan inbox for recent Airtel credit SMS
                    if (pendingCount > 0) {
                        val filterSenders = (prefs.getString("filter_senders", "AX-AIRBNK-S,AX-AIRTEL,BW-AIRTEL,AIRTEL") ?: "")
                            .split(",")
                            .map { it.trim().uppercase() }
                            .filter { it.isNotEmpty() }
                        checkRecentInboxSms(serverUrl, apiKey, filterSenders)
                    }

                    Log.d(TAG, "Heartbeat OK — ${pendingCount} pending orders")
                } else {
                    Log.w(TAG, "Heartbeat failed: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat error: ${e.message}")
            }
        }.start()
    }

    private fun checkRecentInboxSms(serverUrl: String, apiKey: String, filterSenders: List<String>) {
        try {
            val uri = android.net.Uri.parse("content://sms/inbox")
            val twoMinAgo = System.currentTimeMillis() - 2 * 60 * 1000
            val cursor = contentResolver.query(
                uri,
                arrayOf("address", "body", "date"),
                "date > ?",
                arrayOf(twoMinAgo.toString()),
                "date DESC"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val address = it.getString(0) ?: ""
                    val body = it.getString(1) ?: ""
                    val date = it.getLong(2)

                    val upperAddress = address.uppercase()
                    val matches = filterSenders.isEmpty() || filterSenders.any { filter ->
                        upperAddress.contains(filter) || filter.contains(upperAddress)
                    }

                    if (matches && (body.contains("credited with Rs", ignoreCase = true) || body.contains("Txn ID", ignoreCase = true))) {
                        val lastProcessed = prefs.getString("last_inbox_sms_body", "")
                        if (lastProcessed != body) {
                            prefs.edit().putString("last_inbox_sms_body", body).apply()
                            forwardSmsDirect(serverUrl, apiKey, address, body, date)
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            // READ_SMS not granted; relies on SMS_RECEIVED broadcast
        } catch (e: Exception) {
            Log.w(TAG, "Inbox scan error: ${e.message}")
        }
    }

    private fun forwardSmsDirect(serverUrl: String, apiKey: String, sender: String, body: String, timestamp: Long) {
        try {
            val json = JSONObject().apply {
                put("sender", sender)
                put("body", body)
                put("timestamp", timestamp)
                put("source", "android_inbox_scan")
            }
            val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/sms/airtel")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Source", "famflow-sms-forwarder")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(request).execute().close()
            Log.i(TAG, "Directly forwarded inbox SMS from $sender")
        } catch (e: Exception) {
            Log.w(TAG, "Inbox forward failed: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private fun buildNotification(text: String): Notification {
        val openApp = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamFlow SMS Forwarder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SMS Forwarder", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps SMS forwarding active and syncs with FamFlow"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
