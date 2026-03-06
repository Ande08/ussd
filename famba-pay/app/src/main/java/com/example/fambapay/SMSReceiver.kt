package com.example.fambapay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import java.util.regex.Pattern

class SMSReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val body = sms.displayMessageBody
                val sender = sms.displayOriginatingAddress
                
                Log.d("SMSReceiver", "Mensagem recebida de $sender: $body")
                
                // M-Pesa Detection
                if (body.contains("Recebeste", ignoreCase = true) && 
                   (sender.contains("M-PESA", ignoreCase = true) || sender == "171")) {
                    processMPesa(context, body)
                }
                
                // e-Mola Detection
                if (body.contains("e-Mola", ignoreCase = true) || body.contains("transacao", ignoreCase = true)) {
                    processEMola(context, body)
                }
            }
        }
    }

    private fun processMPesa(context: Context, body: String) {
        // Exemplo: Confirmado DBN5J6ZMIVJ. Recebeste 1.00MT de...
        val pattern = Pattern.compile("Confirmado ([A-Z0-9]+)\\.\\s+Recebeste\\s+([0-9.,]+)MT")
        val matcher = pattern.matcher(body)
        
        if (matcher.find()) {
            val code = matcher.group(1) ?: ""
            val amount = matcher.group(2) ?: ""
            reportToBackend(context, "M-PESA", code, amount)
        }
    }

    private fun processEMola(context: Context, body: String) {
        // Exemplo: ID da transacao: PP260223.2252.f72543. Recebeste 25.00MT...
        val pattern = Pattern.compile("ID da transacao:\\s+([A-Z0-9.]+)\\.\\s+Recebeste\\s+([0-9.,]+)MT")
        val matcher = pattern.matcher(body)
        
        if (matcher.find()) {
            val code = matcher.group(1) ?: ""
            val amount = matcher.group(2) ?: ""
            reportToBackend(context, "E-MOLA", code, amount)
        }
    }

    private fun reportToBackend(context: Context, type: String, code: String, amount: String) {
        val info = "Pagamento $type: $amount MT (Código: $code)"
        Log.i("SMSReceiver", "CAPTURA: $info")
        
        // Show notification
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, "famba_pay_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pagamento Recebido ($type)")
            .setContentText("$amount MT - Código: $code")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        
        // Report to Backend
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                RetrofitClient.api.registerPayment(
                    PaymentConfirmation(type = type, code = code, amount = amount)
                )
                Log.d("SMSReceiver", "✅ Código $code enviado ao servidor com sucesso.")
            } catch (e: Exception) {
                Log.e("SMSReceiver", "❌ Erro ao enviar código ao servidor: ${e.message}")
            }
        }
    }
}
