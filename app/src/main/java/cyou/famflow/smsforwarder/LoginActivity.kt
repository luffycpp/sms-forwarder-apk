package cyou.famflow.smsforwarder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("famflow_sms", MODE_PRIVATE) }
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already logged in, go straight to main
        if (prefs.getString("api_key", null) != null) {
            startMain()
            return
        }

        setContentView(R.layout.activity_login)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val tvStatus = findViewById<TextView>(R.id.tvLoginStatus)

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                tvStatus.text = "Please enter email and password"
                tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.red))
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            btnLogin.text = "Signing in..."
            tvStatus.text = ""

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { doLogin(email, password) }
                btnLogin.isEnabled = true
                btnLogin.text = "Sign In"

                if (result.success) {
                    // Save credentials
                    prefs.edit()
                        .putString("api_key", result.apiKey)
                        .putString("webhook_url", result.webhookUrl)
                        .putString("filter_senders", result.filterSenders)
                        .putString("merchant_name", result.merchantName)
                        .putBoolean("enabled", true)
                        .apply()
                    startMain()
                } else {
                    tvStatus.text = result.error ?: "Sign in failed"
                    tvStatus.setTextColor(androidx.core.content.ContextCompat.getColor(this@LoginActivity, R.color.red))
                }
            }
        }
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private data class LoginResult(
        val success: Boolean,
        val apiKey: String? = null,
        val webhookUrl: String? = null,
        val filterSenders: String? = null,
        val merchantName: String? = null,
        val error: String? = null
    )

    private fun doLogin(email: String, password: String): LoginResult {
        return try {
            val serverUrl = prefs.getString("server_url", "https://famflow.cyou") ?: "https://famflow.cyou"
            val deviceId = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("deviceId", deviceId)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$serverUrl/api/app/auth")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            val data = JSONObject(responseBody)

            if (data.optBoolean("success", false)) {
                LoginResult(
                    success = true,
                    apiKey = data.optString("apiKey"),
                    webhookUrl = data.optString("webhookUrl"),
                    filterSenders = data.optString("filterSenders", "AX-AIRBNK-S,AX-AIRTEL,BW-AIRTEL,AIRTEL"),
                    merchantName = data.optString("merchantName")
                )
            } else {
                LoginResult(success = false, error = data.optString("error", "Login failed"))
            }
        } catch (e: Exception) {
            LoginResult(success = false, error = "Cannot connect to server: ${e.message}")
        }
    }
}
