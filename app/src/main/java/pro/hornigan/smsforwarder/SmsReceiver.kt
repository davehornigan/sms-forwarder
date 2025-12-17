package pro.hornigan.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            // Group multipart messages by sender
            val messagesBySender = mutableMapOf<String, String>()
            for (message in messages) {
                val sender = message.originatingAddress
                if (sender != null) {
                    messagesBySender[sender] = (messagesBySender[sender] ?: "") + message.messageBody
                }
            }

            val subscriptionId = intent.getIntExtra("subscription", -1)

            for ((sender, body) in messagesBySender) {
                val serviceIntent = Intent(context, ForwardingService::class.java).apply {
                    putExtra("sender", sender)
                    putExtra("body", body)
                    putExtra("subscriptionId", subscriptionId)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}