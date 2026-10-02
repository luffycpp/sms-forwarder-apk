package cyou.famflow.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

    private val prefs by lazy { getSharedPreferences("famflow_sms", MODE_PRIVATE) }
    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())
    private var isConnected = false

    private lateinit var tvStatusLabel: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var tvMerchantName: TextView
    private lateinit var tvLastPing: TextView
    private lateinit var statusDot: View
    private lateinit var pulseRing: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If not logged in, go to login
        if (prefs.getString("api_key", null) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvStatusLabel = findViewById(R.id.tvStatusLabel)
        tvStatusSub = findViewById(R.id.tvStatusSub)
        tvMerchantName = findViewById(R.id.tvMerchantName)
        tvLastPing = findViewById(R.id.tvLastPing)
        statusDot = findViewById(R.id.statusDot)
        pulseRing = findViewById(R.id.pulseRing)

        // Show merchant name
        tvMerchantName.text = prefs.getString("merchant_name", "FamFlow Account") ?: "FamFlow Account"

        // Start foreground service
        startForwarderService()

        // Sign out
        findViewById<Button>(R.id.btnSignOut).setOnClickListener {
            prefs.edit().clear().apply()
            stopForwarderService()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Set disconnected state initially, service will update via heartbeat
        setStatus(false)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        scheduleUiRefresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    private fun refreshUi() {
        val lastPingMs = prefs.getLong("last_heartbeat_ms", 0)
        val isLive = lastPingMs > 0 && (System.currentTimeMillis() - lastPingMs) < 45_000
        setStatus(isLive)
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeStr = if (lastPingMs > 0) fmt.format(Date(lastPingMs)) else "Syncing..."
        tvLastPing.text = "Last sync: $timeStr"
    }

    private fun scheduleUiRefresh() {
        handler.postDelayed({
            refreshUi()
            scheduleUiRefresh()
        }, 5000)
    }

    fun setStatus(connected: Boolean) {
        isConnected = connected
        if (connected) {
            tvStatusLabel.text = "Connected"
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.green))
            tvStatusSub.text = "Listening for Airtel Bank SMS"
            statusDot.setBackgroundResource(R.drawable.status_dot_green)
            pulseRing.setBackgroundResource(R.drawable.pulse_ring_green)
        } else {
            tvStatusLabel.text = "Connecting..."
            tvStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvStatusSub.text = "Waiting for server connection"
            statusDot.setBackgroundResource(R.drawable.status_dot_grey)
            pulseRing.setBackgroundResource(R.drawable.pulse_ring_grey)
        }
    }

    private fun startForwarderService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, ForwarderService::class.java))
        } else {
            startService(Intent(this, ForwarderService::class.java))
        }
    }

    private fun stopForwarderService() {
        stopService(Intent(this, ForwarderService::class.java))
    }
}
