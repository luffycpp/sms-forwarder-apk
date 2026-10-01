package cyou.famflow.smsforwarder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val PERMISSIONS_REQUEST_CODE = 100
    private val prefs by lazy { getSharedPreferences("famflow_sms", MODE_PRIVATE) }

    private lateinit var tvStatus: TextView
    private lateinit var etWebhookUrl: EditText
    private lateinit var etFilterSenders: EditText
    private lateinit var switchEnabled: Switch
    private lateinit var btnSave: Button
    private lateinit var btnTest: Button
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etFilterSenders = findViewById(R.id.etFilterSenders)
        switchEnabled = findViewById(R.id.switchEnabled)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)

        // Load saved prefs
        etWebhookUrl.setText(prefs.getString("webhook_url", "https://famflow.cyou/api/sms/airtel"))
        etFilterSenders.setText(prefs.getString("filter_senders", "AX-AIRTEL,BW-AIRTEL,AIRTEL,AIRINB,JK-AIRTEL"))
        switchEnabled.isChecked = prefs.getBoolean("enabled", true)

        loadLogs()
        checkAndRequestPermissions()

        btnSave.setOnClickListener {
            saveSettings()
            startForwarderService()
            Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show()
        }

        btnTest.setOnClickListener {
            testWebhook()
        }

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enabled", isChecked).apply()
            updateStatusUI()
            if (isChecked) startForwarderService() else stopForwarderService()
        }

        updateStatusUI()
        startForwarderService()
    }

    private fun saveSettings() {
        prefs.edit()
            .putString("webhook_url", etWebhookUrl.text.toString().trim())
            .putString("filter_senders", etFilterSenders.text.toString().trim().uppercase())
            .putBoolean("enabled", switchEnabled.isChecked)
            .apply()
    }

    private fun testWebhook() {
        val url = etWebhookUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "❌ Enter webhook URL first", Toast.LENGTH_SHORT).show()
            return
        }

        btnTest.isEnabled = false
        btnTest.text = "Testing..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val json = JSONObject().apply {
                        put("sender", "AX-AIRTEL")
                        put("body", "Your A/c XX1234 debited Rs.500.00 on 01-10-26. UPI Ref: 123456789. If not done by you, call 400. -Airtel Bank")
                        put("timestamp", System.currentTimeMillis())
                        put("source", "test")
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(body).build()
                    val response = client.newCall(request).execute()
                    "HTTP ${response.code}: ${response.body?.string()?.take(100)}"
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
            }
            btnTest.isEnabled = true
            btnTest.text = "🧪 Test Webhook"
            appendLog("TEST → $result")
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    private fun startForwarderService() {
        if (prefs.getBoolean("enabled", true)) {
            val intent = Intent(this, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun stopForwarderService() {
        stopService(Intent(this, ForwarderService::class.java))
    }

    private fun updateStatusUI() {
        val enabled = prefs.getBoolean("enabled", true)
        tvStatus.text = if (enabled) "🟢 Forwarder Active" else "🔴 Forwarder Stopped"
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.green else R.color.red)
        )
    }

    fun appendLog(message: String) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val currentLog = prefs.getString("logs", "") ?: ""
        val newLog = "[$time] $message\n$currentLog"
        val trimmed = newLog.lines().take(50).joinToString("\n") // keep last 50 entries
        prefs.edit().putString("logs", trimmed).apply()
        runOnUiThread {
            tvLogs.text = trimmed
        }
    }

    private fun loadLogs() {
        tvLogs.text = prefs.getString("logs", "No logs yet. Waiting for SMS...") ?: ""
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, denied.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(this, "⚠️ SMS permission denied — app won't work!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }
}
