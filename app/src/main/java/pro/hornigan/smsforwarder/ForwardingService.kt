package pro.hornigan.smsforwarder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ForwardingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sender = intent?.getStringExtra("sender") ?: ""
        val body = intent?.getStringExtra("body") ?: ""
        val subscriptionId = intent?.getIntExtra("subscriptionId", -1) ?: -1
        val testRecipient = intent?.getStringExtra("test_recipient")
        val isTest = intent?.getBooleanExtra("is_test", false) ?: false

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "sms_forwarder_channel")
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.forwarding_sms))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(1, notification)

        scope.launch {
            val subscriptionManager = ContextCompat.getSystemService(this@ForwardingService, SubscriptionManager::class.java)
            val subscriptionInfo = subscriptionManager?.let { getSubscriptionInfo(it, subscriptionId) }
            val recipient = testRecipient ?: subscriptionManager?.let { getRecipientNumber(it, subscriptionInfo) }
            val simSlot = subscriptionInfo?.simSlotIndex
            forwardSms(sender, body, recipient, simSlot, subscriptionId)

            // Under test, do not stop the service, so the notification remains visible.
            if (!isTest) {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun getSubscriptionInfo(subscriptionManager: SubscriptionManager, subscriptionId: Int): SubscriptionInfo? {
        if (subscriptionId == -1) return null
        return try {
            subscriptionManager.getActiveSubscriptionInfo(subscriptionId)
        } catch (e: SecurityException) {
            e.printStackTrace()
            null
        }
    }

    private fun getRecipientNumber(subscriptionManager: SubscriptionManager, subscriptionInfo: SubscriptionInfo?): String? {
        if (subscriptionInfo == null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            subscriptionManager.getPhoneNumber(subscriptionInfo.subscriptionId)
        } else {
            @Suppress("DEPRECATION")
            subscriptionInfo.number
        }
    }

    private fun forwardSms(sender: String, body: String, recipient: String?, simSlot: Int?, subscriptionId: Int) {
        val sharedPreferences = getSharedPreferences("sms_forwarder_prefs", Context.MODE_PRIVATE)

        val recipientError = if (subscriptionId != -1 && recipient.isNullOrBlank()) {
            applicationContext.getString(R.string.recipient_error)
        } else null

        val simSlotName = when {
            simSlot != null -> {
                sharedPreferences.getString("sim_slot_name_$simSlot", "")?.takeIf { it.isNotBlank() }
                    ?: applicationContext.getString(R.string.default_slot_name, simSlot + 1)
            }
            subscriptionId == -1 -> applicationContext.getString(R.string.test_slot_name)
            else -> applicationContext.getString(R.string.unknown_slot_name)
        }

        if (simSlot == null) {
            val error = "Cannot forward SMS: SIM slot is not identified."
            sharedPreferences.edit {
                val currentLogs = sharedPreferences.getStringSet("error_logs", mutableSetOf()) ?: mutableSetOf()
                val newLogs = currentLogs.toMutableSet()
                newLogs.add("${System.currentTimeMillis()}|[System] $error")
                putStringSet("error_logs", newLogs)
            }
            return
        }

        val webhookUrl = sharedPreferences.getString("webhook_url_$simSlot", null)
        val userAgent = sharedPreferences.getString("user_agent_$simSlot", "")

        var success = false
        var networkError: String? = null

        if (webhookUrl.isNullOrBlank()) {
            networkError = applicationContext.getString(R.string.webhook_not_set_error)
        } else {
            try {
                val url = URL(webhookUrl)
                val jsonObject = JSONObject().apply {
                    put("sender", sender)
                    put("body", body)
                    put("recipient", recipient ?: "")
                    put("simSlotName", simSlotName)
                }

                with(url.openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    if (!userAgent.isNullOrBlank()) {
                        setRequestProperty("User-Agent", userAgent)
                    }
                    doOutput = true
                    OutputStreamWriter(outputStream).use {
                        it.write(jsonObject.toString())
                    }
                    val responseCode = this.responseCode
                    if (responseCode in 200..299) {
                        success = true
                    } else {
                        networkError = applicationContext.getString(R.string.server_response_error, responseCode)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                networkError = e.localizedMessage ?: applicationContext.getString(R.string.unknown_connection_error)
            }
        }

        sharedPreferences.edit {
            val statKeySuffix = "_$simSlot"
            val totalForwardedKey = "total_forwarded$statKeySuffix"
            val successfulForwardsKey = "successful_forwards$statKeySuffix"
            val failedForwardsKey = "failed_forwards$statKeySuffix"

            putInt(totalForwardedKey, sharedPreferences.getInt(totalForwardedKey, 0) + 1)
            if (success) {
                putInt(successfulForwardsKey, sharedPreferences.getInt(successfulForwardsKey, 0) + 1)
            } else {
                putInt(failedForwardsKey, sharedPreferences.getInt(failedForwardsKey, 0) + 1)

                val errorsToLog = listOfNotNull(recipientError, networkError)
                if (errorsToLog.isNotEmpty()) {
                    val currentLogs = sharedPreferences.getStringSet("error_logs", mutableSetOf()) ?: mutableSetOf()
                    val newLogs = currentLogs.toMutableSet()
                    errorsToLog.forEach { error ->
                        newLogs.add("${System.currentTimeMillis()}|[SIM ${simSlotName}] $error")
                    }
                    putStringSet("error_logs", newLogs)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sms_forwarder_channel",
                applicationContext.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}