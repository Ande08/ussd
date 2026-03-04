package com.example.siminfo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

data class QueuedTransfer(
    val simId: Int,
    val carrierName: String,
    val amount: String,
    val number: String,
    val timestamp: Long = System.currentTimeMillis()
)

class MainActivity : ComponentActivity() {
    
    private val ussdBalances = mutableStateMapOf<Int, String>()
    private var isConsultingAll = mutableStateOf(false)
    private var consultationStatus = mutableStateOf("Consultar Todos")
    private var pendingSims = mutableListOf<SubscriptionInfo>()
    private var currentSimId: Int? = null

    // Backend Job tracking
    private var currentBackendJobId: Int? = null
    private var isPollingPaused = false
    private val connectionLogs = mutableStateListOf<String>()
    
    // Transfer Queue State
    private val waitingList = mutableStateListOf<QueuedTransfer>()
    private var activeTransferInfo: QueuedTransfer? = null
    private var retryCount = 0
    private var transferTimeoutHandler = Handler(Looper.getMainLooper())
    private var transferTimeoutRunnable: Runnable? = null

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.siminfo.USSD_RESULT" -> {
                    val text = intent.getStringExtra("ussd_text") ?: return
                    val explicitSubId = intent.getIntExtra("subscription_id", -1)
                    val extracted = extractInternetBalance(text)
                    if (extracted != null) {
                        val targetId = if (explicitSubId != -1) explicitSubId else currentSimId
                        if (targetId != null) processResult(targetId, extracted)
                    }
                }
                "com.example.siminfo.TRANSFER_STATUS" -> {
                    val status = intent.getStringExtra("status")
                    val message = intent.getStringExtra("message") ?: ""
                    handleTransferStatus(status, message)
                }
            }
        }
    }

    private fun handleTransferStatus(status: String?, message: String) {
        cancelTransferTimeout()
        Log.d("MainActivity", "handleTransferStatus: StatusReceived=$status, Msg=$message")
        val info = activeTransferInfo ?: run {
            Log.e("MainActivity", "handleTransferStatus: No activeTransferInfo found!")
            return
        }

        if (status == "SUCCESS") {
            Log.d("MainActivity", "SUCCESS detected. Recipient: ${info.number}, Amount: ${info.amount}")
            Toast.makeText(this, "Transferência Concluída!", Toast.LENGTH_LONG).show()
            showTransferNotification(info.number, info.amount)

            // Deduct balance locally
            val currentStr = ussdBalances[info.simId]
            if (currentStr != null) {
                val currentMB = parseBalanceToMb(currentStr)
                val transferredMB = info.amount.toDoubleOrNull() ?: 0.0
                val newMB = (currentMB - transferredMB).coerceAtLeast(0.0)
                val formatted = formatBalance(newMB)
                ussdBalances[info.simId] = formatted // State update
                Log.d("MainActivity", "Local balance updated: $currentStr -> $formatted")
            }

            reportJobStatus("SUCESSO")

            activeTransferInfo = null
            retryCount = 0
            pendingTransferAmount.value = null
            pendingTransferNumber.value = null
        } else {
            Log.w("MainActivity", "FAILURE reported. Msg: $message")
            reportJobStatus("FALHA")
            attemptRetry(info)
        }
    }

    private fun reportJobStatus(status: String) {
        val jobId = currentBackendJobId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.api.updateTransferStatus(UpdateStatusRequest(jobId, status))
                Log.d("MainActivity", "Backend notified: Job $jobId is $status. Server msg: ${response.message}")
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to update backend for job $jobId: ${e.message}")
            } finally {
                currentBackendJobId = null
                isPollingPaused = false // Resume polling
            }
        }
    }

    private fun attemptRetry(info: QueuedTransfer) {
        retryCount++
        if (retryCount < 2) {
            Log.d("MainActivity", "Retrying transfer (Attempt $retryCount)")
            Toast.makeText(this, "Falhou. Tentando novamente...", Toast.LENGTH_SHORT).show()
            startTransferInternal(info)
        } else {
            Log.d("MainActivity", "Fail limit reached. Adding to Waiting List.")
            Toast.makeText(this, "Falhou 2x. Adicionado à Lista de Espera.", Toast.LENGTH_LONG).show()
            waitingList.add(info)
            activeTransferInfo = null
            retryCount = 0
            pendingTransferAmount.value = null
            pendingTransferNumber.value = null
        }
    }

    private fun startTransferTimeout() {
        cancelTransferTimeout()
        transferTimeoutRunnable = Runnable {
            Log.w("MainActivity", "Transfer Timeout. No success message detected.")
            activeTransferInfo?.let { attemptRetry(it) }
        }
        transferTimeoutHandler.postDelayed(transferTimeoutRunnable!!, 45000) // 45s for multi-step
    }

    private fun cancelTransferTimeout() {
        transferTimeoutRunnable?.let { transferTimeoutHandler.removeCallbacks(it) }
        transferTimeoutRunnable = null
    }

    private fun processResult(subId: Int, extracted: String) {
        if (subId == currentSimId) {
            currentSimId = null
            ussdBalances[subId] = extracted
            if (isConsultingAll.value) {
                Handler(Looper.getMainLooper()).postDelayed({ triggerNextSim(this) }, 7000) 
            }
        } else {
            ussdBalances[subId] = extracted
            lastExtractedBalance.value = extracted
        }
    }

    private fun triggerNextSim(context: Context) {
        if (!isConsultingAll.value) return
        if (pendingSims.isNotEmpty()) {
            val nextSim = pendingSims.removeAt(0)
            currentSimId = nextSim.subscriptionId
            consultationStatus.value = "SIM ${nextSim.simSlotIndex + 1}..."
            checkVodacomBalance(context, nextSim.subscriptionId) { result ->
                val extracted = extractInternetBalance(result)
                if (extracted != null && currentSimId == nextSim.subscriptionId) processResult(nextSim.subscriptionId, extracted)
                else if (result.contains("Falha") && currentSimId == nextSim.subscriptionId) callSpecificUssd(context, nextSim, "*100#")
            }
        } else {
            isConsultingAll.value = false
            consultationStatus.value = "Consultar Todos"
            Toast.makeText(context, "Consulta concluída!", Toast.LENGTH_LONG).show()
        }
    }

    fun startUniversalConsultation(context: Context, sims: List<SubscriptionInfo>) {
        if (sims.isEmpty()) return
        val vodacomSims = sims.filter { info ->
            val name = info.carrierName.toString() + info.displayName.toString()
            name.contains("Vodacom", ignoreCase = true)
        }
        if (vodacomSims.isEmpty()) {
            Toast.makeText(context, "Nenhum chip Vodacom detectado.", Toast.LENGTH_SHORT).show()
            return
        }
        isConsultingAll.value = true
        pendingSims.clear()
        pendingSims.addAll(vodacomSims)
        triggerNextSim(context)
    }

    private fun findBestSimForTransfer(amountMb: Double): SubscriptionInfo? {
        val sims = getSimInfo(this).filter { info ->
            val name = info.carrierName.toString() + info.displayName.toString()
            name.contains("Vodacom", ignoreCase = true)
        }
        
        // Sort sims by balance descends, pick the first one >= amount
        return sims.mapNotNull { sim ->
            val balanceStr = ussdBalances[sim.subscriptionId]
            if (balanceStr != null) {
                val mb = parseBalanceToMb(balanceStr)
                sim to mb
            } else null
        }.filter { it.second >= amountMb }
         .maxByOrNull { it.second }?.first
    }

    fun startSmartTransfer(context: Context, amount: String, number: String) {
        val amountMb = amount.toDoubleOrNull() ?: 0.0
        if (amountMb <= 0 || number.isBlank()) {
            Toast.makeText(context, "Preencha número e quantidade válida.", Toast.LENGTH_SHORT).show()
            return
        }

        val bestSim = findBestSimForTransfer(amountMb)
        if (bestSim != null) {
            Toast.makeText(context, "Chip selecionado: ${bestSim.displayName}", Toast.LENGTH_SHORT).show()
            startDataTransfer(context, bestSim, amount, number)
        } else {
            Toast.makeText(context, "Nenhum chip com saldo suficiente (${amount} MB).", Toast.LENGTH_LONG).show()
        }
    }

    fun startDataTransfer(context: Context, info: SubscriptionInfo, amount: String, number: String) {
        if (amount.isBlank() || number.isBlank()) {
            Toast.makeText(context, "Preencha número e quantidade.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("MainActivity", "Starting Data Transfer: $amount MB to $number from SIM ${info.subscriptionId}")
        val transferInfo = QueuedTransfer(info.subscriptionId, info.displayName.toString(), amount, number)
        activeTransferInfo = transferInfo
        retryCount = 0
        startTransferInternal(transferInfo)
    }

    private fun startTransferInternal(info: QueuedTransfer) {
        pendingTransferAmount.value = info.amount
        pendingTransferNumber.value = info.number
        
        val subManager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            val subInfo = subManager.activeSubscriptionInfoList?.find { it.subscriptionId == info.simId }
            if (subInfo != null) {
                startTransferTimeout()
                callSpecificUssd(this, subInfo, "*162#")
            } else {
                Toast.makeText(this, "Chip não encontrado!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun callSpecificUssd(context: Context, info: SubscriptionInfo, ussd: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val accounts = telecomManager.callCapablePhoneAccounts
        var handle: PhoneAccountHandle? = null
        for (acc in accounts) {
            if (acc.id.contains(info.subscriptionId.toString()) || acc.id.endsWith(info.simSlotIndex.toString())) {
                handle = acc
                break
            }
        }
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:" + Uri.encode(ussd))
            handle?.let { putExtra("android.telecom.extra.PHONE_ACCOUNT_HANDLE", it) }
            putExtra("com.android.phone.force.slot", true)
            putExtra("com.android.phone.extra.slot", info.simSlotIndex)
            putExtra("simSlot", info.simSlotIndex)
            putExtra("subscription", info.subscriptionId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        val lastExtractedBalance = mutableStateOf<String?>(null)
        val pendingTransferAmount = mutableStateOf<String?>(null)
        val pendingTransferNumber = mutableStateOf<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction("com.example.siminfo.USSD_RESULT")
            addAction("com.example.siminfo.TRANSFER_STATUS")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(resultReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }

        startBackendPolling()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SimInfoScreen(
                        isConsultingAll = isConsultingAll.value,
                        consultationText = consultationStatus.value,
                        waitingList = waitingList,
                        onConsultAll = { sims -> startUniversalConsultation(this, sims) },
                        onTransfer = { info, amount, num -> startDataTransfer(this, info, amount, num) },
                        onSmartTransfer = { amount, num -> startSmartTransfer(this, amount, num) },
                        ussdBalancesMap = ussdBalances,
                        connectionLogs = connectionLogs,

                        onRetryQueued = { q -> 
                            val sim = getSimInfo(this).find { it.subscriptionId == q.simId }
                            if (sim != null) startDataTransfer(this, sim, q.amount, q.number)
                            waitingList.remove(q) 
                        },
                        onRemoveQueued = { waitingList.remove(it) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(resultReceiver)
        cancelTransferTimeout()
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$time] $msg"
        // Keep only the last 50 logs to save memory
        if (connectionLogs.size >= 50) connectionLogs.removeAt(0)
        connectionLogs.add(logEntry)
        Log.d("MainActivity", "AppLog: $msg")
    }

    private fun startBackendPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            addLog("Iniciando conexão com o Servidor...")
            while (true) {
                if (!isPollingPaused && activeTransferInfo == null && currentBackendJobId == null) {
                    try {
                        val response = RetrofitClient.api.getPendingTransfer()
                        if (response.job != null) {
                            val job = response.job
                            addLog("🟢 Pedido #${job.id} Recebido: ${job.amount}MB p/ ${job.number}")
                            withContext(Dispatchers.Main) {
                                isPollingPaused = true
                                currentBackendJobId = job.id
                                // Start smart transfer automatically
                                startSmartTransfer(this@MainActivity, job.amount, job.number)
                            }
                        } else {
                            // Only log heartbeat occasionally or don't log empty queues to avoid spam
                        }
                    } catch (e: Exception) {
                        addLog("🔴 Erro de Conexão: Servidor Offline ou IP Incorreto.")
                    }
                }
                delay(7000) // Poll every 7 seconds
            }
        }
    }

    private fun reportJobStatus(status: String) {
        val jobId = currentBackendJobId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                addLog("Enviando status pro Servidor: $status")
                val response = RetrofitClient.api.updateTransferStatus(UpdateStatusRequest(jobId, status))
                addLog("✅ Servidor confirmou: ${response.message}")
            } catch (e: Exception) {
                addLog("⚠️ Erro ao atualizar status: Servidor inatingível.")
            } finally {
                currentBackendJobId = null
                isPollingPaused = false // Resume polling
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Transferências"
            val descriptionText = "Notificações de sucesso de transferência"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("transfer_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showTransferNotification(number: String, amount: String) {
        val builder = NotificationCompat.Builder(this, "transfer_channel")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Transferência Realizada")
            .setContentText("Enviado com sucesso: $amount MB para $number")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager: NotificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}

@Composable
fun SimInfoScreen(
    isConsultingAll: Boolean,
    consultationText: String,
    waitingList: List<QueuedTransfer>,
    onConsultAll: (List<SubscriptionInfo>) -> Unit,
    onTransfer: (SubscriptionInfo, String, String) -> Unit,
    onSmartTransfer: (String, String) -> Unit,
    ussdBalancesMap: Map<Int, String>,
    connectionLogs: List<String>,
    onRetryQueued: (QueuedTransfer) -> Unit,
    onRemoveQueued: (QueuedTransfer) -> Unit
) {
    val context = LocalContext.current
    var simList by remember { mutableStateOf<List<SubscriptionInfo>>(emptyList()) }
    var transferAmount by remember { mutableStateOf("") }
    var transferNumber by remember { mutableStateOf("") }
    val isAccessibilityEnabled = isAccessibilityServiceEnabled(context)

    val permissions = mutableListOf(
        Manifest.permission.READ_PHONE_STATE, 
        Manifest.permission.CALL_PHONE, 
        Manifest.permission.RECEIVE_SMS, 
        Manifest.permission.READ_SMS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    LaunchedEffect(Unit) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            simList = getSimInfo(context)
        } else {
            launcher.launch(permissions.toTypedArray())
        }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(text = "SIM Info & Transfer", style = MaterialTheme.typography.headlineMedium)
            
            Spacer(modifier = Modifier.height(12.dp))

            if (!isAccessibilityEnabled) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.padding(bottom = 16.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Serviço de Acessibilidade desativado. Ative 'SIM Info Reader'.")
                        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Ativar")
                        }
                    }
                }
            }

            // Global Transfer Inputs
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Dados para Transferência", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = transferNumber,
                        onValueChange = { transferNumber = it },
                        label = { Text("Número do recipiente") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val pasteText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!pasteText.isNullOrBlank()) {
                                    val digitsOnly = pasteText.filter { it.isDigit() }
                                    if (digitsOnly.isNotEmpty()) {
                                        transferNumber = digitsOnly
                                    }
                                }
                            }) {
                                Text("📋", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = transferAmount,
                        onValueChange = { transferAmount = it },
                        label = { Text("Quantidade (MB)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onSmartTransfer(transferAmount, transferNumber) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Transferência Inteligente (Auto SIM)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Chips Ativos", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onConsultAll(simList) }, enabled = !isConsultingAll) {
                    Text(consultationText)
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(simList) { info ->
                    val balance = ussdBalancesMap[info.subscriptionId]
                    SimCardItem(info, balance, transferAmount, transferNumber, onTransfer)
                }
                
                // Connection Logs Section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "Logs do Servidor (Auto-Queue)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(150.dp)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp).fillMaxSize(), reverseLayout = true) {
                            items(connectionLogs.reversed()) { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                    color = if (log.contains("🔴") || log.contains("⚠️")) Color.Red else if (log.contains("🟢") || log.contains("✅")) Color.Green else Color.LightGray
                                )
                            }
                        }
                    }
                }

                if (waitingList.isNotEmpty()) {
                    item {
                        Divider(modifier = Modifier.padding(vertical = 16.dp))
                        Text(text = "Lista de Espera (Falhas)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(waitingList) { queued ->
                        QueuedTransferItem(queued, onRetryQueued, onRemoveQueued)
                    }
                }
            }
        }
    }
}

@Composable
fun QueuedTransferItem(queued: QueuedTransfer, onRetry: (QueuedTransfer) -> Unit, onRemove: (QueuedTransfer) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${queued.amount} MB -> ${queued.number}", fontWeight = FontWeight.Bold)
                Text("Chip: ${queued.carrierName}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onRemove(queued) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remover", tint = Color.Red)
            }
            Button(onClick = { onRetry(queued) }) {
                Text("Tentar")
            }
        }
    }
}

@Composable
fun SimCardItem(info: SubscriptionInfo, balance: String?, amount: String, number: String, onTransfer: (SubscriptionInfo, String, String) -> Unit) {
    val context = LocalContext.current
    val isVodacom = info.carrierName.toString().contains("Vodacom", ignoreCase = true) || info.displayName.toString().contains("Vodacom", ignoreCase = true)

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Slot: ${info.simSlotIndex + 1} - ${info.displayName}", style = MaterialTheme.typography.titleMedium)
            if (balance != null) {
                Text(text = "Saldo: $balance", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            if (isVodacom) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { 
                            checkVodacomBalance(context, info.subscriptionId) { result ->
                                val extracted = extractInternetBalance(result)
                                 if (extracted != null) MainActivity.lastExtractedBalance.value = extracted
                            }
                        }, 
                        modifier = Modifier.weight(1f)
                    ) { Text("Saldo") }
                    Button(onClick = { onTransfer(info, amount, number) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                        Text("Transferir")
                    }
                }
            }
        }
    }
}

fun parseBalanceToMb(balanceStr: String): Double {
    return try {
        val normalized = balanceStr.uppercase().replace(",", ".")
        val value = normalized.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        when {
            normalized.contains("GB") -> value * 1024.0
            normalized.contains("KB") -> value / 1024.0
            else -> value // MB or Bytes (treated as MB for simplicity if unit missing)
        }
    } catch (_: Exception) { 0.0 }
}

fun formatBalance(mbValue: Double): String {
    return if (mbValue >= 1024.0) {
        String.format("%.2f GB", mbValue / 1024.0)
    } else if (mbValue > 0) {
        String.format("%.0f MB", mbValue)
    } else {
        "0 MB"
    }
}

fun extractInternetBalance(response: String): String? {
    val mensalRegex = Regex("Mensal:\\s*(\\d+\\s*(?:MB|GB|KB|Bytes))", RegexOption.IGNORE_CASE)
    val mensalMatch = mensalRegex.find(response)
    if (mensalMatch != null) return mensalMatch.groupValues[1]
    val internetRegex = Regex("Internet:\\s*(\\d+\\s*(?:MB|GB|KB|Bytes))", RegexOption.IGNORE_CASE)
    val internetMatch = internetRegex.find(response)
    if (internetMatch != null) return internetMatch.groupValues[1]
    return null
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = context.packageName + "/" + USSDService::class.java.canonicalName
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabledServices?.contains(service) == true
}

fun checkVodacomBalance(context: Context, subId: Int, onResult: (String) -> Unit) {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subSpecificManager = telephonyManager.createForSubscriptionId(subId)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
        try {
            subSpecificManager.sendUssdRequest("*100#", object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(telephonyManager: TelephonyManager?, request: String?, response: CharSequence?) {
                    super.onReceiveUssdResponse(telephonyManager, request, response)
                    onResult(response?.toString() ?: "")
                }
                override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager?, request: String?, failureCode: Int) {
                    super.onReceiveUssdResponseFailed(telephonyManager, request, failureCode)
                    onResult("Falha")
                }
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) { onResult("Erro") }
    } else onResult("Sem permissão")
}

fun getSimInfo(context: Context): List<SubscriptionInfo> {
    val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    return (if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
        subscriptionManager.activeSubscriptionInfoList ?: emptyList()
    } else emptyList())
}
