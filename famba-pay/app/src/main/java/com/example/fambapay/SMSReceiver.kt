package com.example.fambapay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.content.SharedPreferences
import java.util.regex.Pattern
import kotlinx.coroutines.launch
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class SMSReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val sharedPrefs = context.getSharedPreferences("FambaPayPrefs", Context.MODE_PRIVATE)

            for (sms in messages) {
                val body = sms.displayMessageBody
                val sender = sms.displayOriginatingAddress
                
                Log.d("SMSReceiver", "Mensagem recebida de $sender: $body")
                
                // M-Pesa Detection
                if (body.contains("Recebeste", ignoreCase = true) && 
                   (sender.contains("M-PESA", ignoreCase = true) || sender == "171")) {
                    processMPesa(context, body, sharedPrefs)
                }
                
                // e-Mola Detection
                if (body.contains("e-Mola", ignoreCase = true) || body.contains("transacao", ignoreCase = true)) {
                    processEMola(context, body, sharedPrefs)
                }
            }
        }
    }

    private fun processMPesa(context: Context, body: String, sharedPrefs: SharedPreferences) {
        // Exemplo: Confirmado DBN5J6ZMIVJ. Recebeste 1.00MT de...
        val pattern = Pattern.compile("Confirmado ([A-Z0-9]+)\\.\\s+Recebeste\\s+([0-9.,]+)MT")
        val matcher = pattern.matcher(body)
        
        if (matcher.find()) {
            val code = matcher.group(1) ?: ""
            val amount = matcher.group(2) ?: ""
            evaluatePayment(context, "M-PESA", code, amount, sharedPrefs)
        }
    }

    private fun processEMola(context: Context, body: String, sharedPrefs: SharedPreferences) {
        // Exemplo: ID da transacao: PP260223.2252.f72543. Recebeste 25.00MT...
        val pattern = Pattern.compile("ID da transacao:\\s+([A-Z0-9.]+)\\.\\s+Recebeste\\s+([0-9.,]+)MT")
        val matcher = pattern.matcher(body)
        
        if (matcher.find()) {
            val code = matcher.group(1) ?: ""
            val amount = matcher.group(2) ?: ""
            evaluatePayment(context, "E-MOLA", code, amount, sharedPrefs)
        }
    }

    private fun evaluatePayment(context: Context, type: String, code: String, amount: String, sharedPrefs: SharedPreferences) {
        val targetAmounts = sharedPrefs.getStringSet("target_amounts", emptySet()) ?: emptySet()
        val formattedAmount = amount.replace(",", ".") // Ensure decimal format matches
        
        // Remove trailing .00 if target amounts are simple integers
        val cleanAmount = if (formattedAmount.endsWith(".00")) formattedAmount.dropLast(3) else formattedAmount

        if (targetAmounts.contains(cleanAmount) || targetAmounts.contains(formattedAmount)) {
            sendLog(context, "🟢 Pagamento $type Aceito: $amount MT (Código: $code)")
            reportToBackend(context, type, code, amount)
        } else {
            sendLog(context, "🟡 Pagamento Ignorado: $amount MT não está na lista (Código: $code)")
        }
    }

    private fun sendLog(context: Context, message: String) {
        Log.i("SMSReceiver", message)
        val intent = Intent("FAMBA_PAY_LOGS")
        intent.putExtra("log_msg", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun reportToBackend(context: Context, type: String, code: String, amount: String) {
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
                sendLog(context, "➡️ Enviando $code para o servidor...")
                RetrofitClient.api.registerPayment(
                    PaymentConfirmation(type = type, code = code, amount = amount)
                )
                sendLog(context, "✅ Código $code enviado ao servidor com sucesso.")
            } catch (e: Exception) {
                sendLog(context, "❌ Erro ao enviar $code para o servidor: ${e.message}")
            }
        }
    }
}
