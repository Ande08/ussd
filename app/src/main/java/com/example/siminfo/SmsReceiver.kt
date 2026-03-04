package com.example.siminfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle = intent.extras ?: return
            @Suppress("DEPRECATION")
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            
            // Get Subscription ID from intent
            val subId = bundle.getInt("subscription", -1)
            Log.d("SmsReceiver", "SMS received on subscription: $subId")

            val fullMessage = StringBuilder()
            for (pdu in pdus) {
                val format = bundle.getString("format")
                val message = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }
                fullMessage.append(message.displayMessageBody)
            }

            val text = fullMessage.toString()
            Log.d("SmsReceiver", "Message content: $text")

            // Check if it's a balance message (Carrier usually uses short numbers or specific keywords)
            if (text.contains("Mensal:", ignoreCase = true) || text.contains("Saldo", ignoreCase = true)) {
                val resultIntent = Intent("com.example.siminfo.USSD_RESULT")
                resultIntent.putExtra("ussd_text", text)
                // Pass subId so MainActivity knows which chip this belongs to
                if (subId != -1) {
                    resultIntent.putExtra("subscription_id", subId)
                }
                context.sendBroadcast(resultIntent)
            }
        }
    }
}
