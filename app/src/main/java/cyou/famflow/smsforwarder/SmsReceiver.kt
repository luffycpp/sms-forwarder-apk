package cyou.famflow.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "FamFlowSMS"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences("famflow_sms", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", true)
        if (!isEnabled) return

        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        if (webhookUrl.isEmpty()) {
            Log.w(TAG, "No webhook URL configured")
            return
        }

        val filterSenders = (prefs.getString("filter_senders", "AX-AIRTEL,BW-AIRTEL,AIRTEL,AIRINB") ?: "")
            .split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

        // Parse SMS messages
        val messages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } else {
            @Suppress("DEPRECATION")
            val pdus = intent.extras?.get("pdus") as? Array<*> ?: return
            pdus.mapNotNull { pdu ->
                SmsMessage.createFromPdu(pdu as ByteArray)
            }.toTypedArray()
        }

        if (messages.isNullOrEmpty()) return

        // Group multipart SMS
        val sender = messages[0].originatingAddress ?: "UNKNOWN"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages[0].timestampMillis

        Log.d(TAG, "SMS from: $sender | Body: $body")

        // Check filter — if filters are set, only forward matching senders
        val senderUpper = sender.uppercase()
        val shouldForward = filterSenders.isEmpty() || filterSenders.any { filter ->
            senderUpper.contains(filter) || filter.contains(senderUpper)
        }

        if (!shouldForward) {
            Log.d(TAG, "Skipping SMS from $sender (not in filter list)")
            return
        }

        Log.i(TAG, "Forwarding SMS from $sender to $webhookUrl")

        // Forward asynchronously
        val pendingResult = goAsync()
        scope.launch {
            try {
                forwardSms(context, webhookUrl, sender, body, timestamp)
                logToPrefs(prefs, "✅ Forwarded from $sender")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to forward SMS", e)
                logToPrefs(prefs, "❌ Failed: ${e.message?.take(60)}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun forwardSms(context: Context, url: String, sender: String, body: String, timestamp: Long) {
        val prefs = context.getSharedPreferences("famflow_sms", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val json = JSONObject().apply {
            put("sender", sender)
            put("body", body)
            put("timestamp", timestamp)
            put("source", "android_sms")
        }

        val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Source", "famflow-sms-forwarder")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        val response = client.newCall(request).execute()
        Log.i("FamFlowSMS", "Webhook response: ${response.code}")
        response.close()
    }

    private fun logToPrefs(prefs: android.content.SharedPreferences, msg: String) {
        val existing = prefs.getString("logs", "") ?: ""
        val lines = existing.lines().take(49)
        val newLog = "$msg\n${lines.joinToString("\n")}"
        prefs.edit().putString("logs", newLog).apply()
    }
}
