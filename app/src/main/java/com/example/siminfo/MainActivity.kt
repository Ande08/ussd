package com.example.siminfo

import java.util.Locale

import android.Manifest
import android.content.BroadcastReceiver
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
import android.view.WindowManager
import android.widget.Toast
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.text.TextUtils
import androidx.activity.result.contract.ActivityResultContracts
import android.content.SharedPreferences
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TileMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.text.style.TextAlign

data class QueuedTransfer(
    val simId: Int,
    val carrierName: String,
    val amount: String,
    val number: String,
    val timestamp: Long = System.currentTimeMillis()
)

// UI State at file level to be accessible by composables and USSDService
val ussdBalances = mutableStateMapOf<Int, String>()
val isBackendPollingEnabled = mutableStateOf(false)
val deviceList = mutableStateListOf<Device>()
val waitingList = mutableStateListOf<QueuedTransfer>()
val isAccessibilityEnabled = mutableStateOf(false)
var currentUsername: String? = null
var currentAccount: String? = null // New: Owner of this device group

// Previously in companion object
val lastExtractedBalance = mutableStateOf<String?>(null)
val pendingTransferAmount = mutableStateOf<String?>(null)
val pendingTransferNumber = mutableStateOf<String?>(null)

class MainActivity : ComponentActivity() {
    
    var isConsultingAll = mutableStateOf(false)
    var consultationStatus = mutableStateOf("Consultar Todos")
    private var pendingSims = mutableListOf<SubscriptionInfo>()
    private var currentSimId: Int? = null

    // Backend Job tracking
    private var currentBackendJobId: Int? = null
    private var isPollingPaused = false
    private val connectionLogs = mutableStateListOf<String>()
    
    // Transfer Queue State
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


    fun submitToCloud(number: String, amount: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                addLog("📤 Enviando pedido cloud: $amount MB -> $number")
                val response = RetrofitClient.api.scheduleTransfer(
                    ScheduleTransferRequest(number, amount, currentUsername) // Pass requester ID
                )
                withContext(Dispatchers.Main) {
                    if (response.id != null) {
                        Toast.makeText(this@MainActivity, "Agendado com Sucesso! (ID ${response.id})", Toast.LENGTH_SHORT).show()
                        addLog("✅ Pedido agendado na nuvem.")
                    } else {
                        Toast.makeText(this@MainActivity, "Erro: ${response.error}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Falha de conexão: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$time] $msg"
        // Keep only the last 50 logs to save memory
        if (connectionLogs.size >= 50) connectionLogs.removeAt(0)
        connectionLogs.add(logEntry)
        Log.d("MainActivity", "AppLog: $msg")
    }

    private fun getBatteryPercentage(): Int {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val level: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            100 // fallback
        }
    }

    private fun startBackendPolling() {
        lifecycleScope.launch(Dispatchers.IO) {
            addLog("A aguardar início automático do Servidor...")
            while (true) {
                if (!isBackendPollingEnabled.value || currentUsername == null) {
                    delay(3000)
                    continue
                }

                // Heartbeat: Send current device status
                try {
                    val totalBalanceStr = ussdBalances.values.sumOf { parseBalanceToMb(it) }.toString()
                    val currentBattery = getBatteryPercentage()
                    RetrofitClient.api.updateDeviceStatus(DeviceStatusRequest(currentUsername!!, totalBalanceStr, !isBackendPollingEnabled.value, currentBattery))
                } catch (e: Exception) {
                    Log.e("MainActivity", "Erro no Heartbeat: ${e.message}")
                }

                // Polling pending jobs
                if (!isPollingPaused && activeTransferInfo == null && currentBackendJobId == null) {
                    try {
                        val response = RetrofitClient.api.getPendingTransfer(PendingRequest(currentUsername!!))
                        if (response.job != null) {
                            val job = response.job
                            addLog("🟢 Pedido #${job.id} Recebido: ${job.amount}MB p/ ${job.number}")
                            withContext(Dispatchers.Main) {
                                isPollingPaused = true
                                currentBackendJobId = job.id
                                // Start smart transfer automatically
                                startSmartTransfer(this@MainActivity, job.amount, job.number)
                            }
                        }
                    } catch (e: Exception) {
                        addLog("🔴 Erro de Conexão: ${e.message}")
                    }
                }
                delay(7000) // Poll every 7 seconds
            }
        }
    }

    private fun reportJobStatus(status: String) {
        val jobId = currentBackendJobId ?: return
        val user = currentUsername ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                addLog("Enviando status pro Servidor: $status")
                val response = RetrofitClient.api.updateTransferStatus(UpdateStatusRequest(jobId, status, user))
                addLog("✅ Servidor confirmou: ${response.message}")
            } catch (e: Exception) {
                addLog("⚠️ Erro ao atualizar status: ${e.message}")
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(this, "Algumas permissões não foram concedidas.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkAccessibilityService() {
        var accessibilityEnabled = 0
        val service = packageName + "/" + USSDService::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {}
        
        var isEnabled = false
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    if (splitter.next().equals(service, ignoreCase = true)) {
                        isEnabled = true
                        break
                    }
                }
            }
        }
        isAccessibilityEnabled.value = isEnabled
    }

    override fun onResume() {
        super.onResume()
        checkAccessibilityService()
    }

    fun startConsultarTodos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissão de chamadas negada.", Toast.LENGTH_SHORT).show()
            return
        }
        val sims = getSimInfo(this)
        if (sims.isEmpty()) {
            Toast.makeText(this, "Nenhum SIM encontrado.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingSims.clear()
        pendingSims.addAll(sims)
        isConsultingAll.value = true
        triggerNextSim(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        checkAndRequestPermissions()
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

        val prefs = getSharedPreferences("FambaPrefs", Context.MODE_PRIVATE)
        currentUsername = prefs.getString("USERNAME", null)
        currentAccount = prefs.getString("ACCOUNT", null)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isLoggedIn by remember { mutableStateOf(currentUsername != null) }

                    if (!isLoggedIn) {
                        LoginScreen(
                            onLoginSuccess = { user, pass, acc ->
                                prefs.edit()
                                    .putString("USERNAME", user)
                                    .putString("PASSWORD", pass)
                                    .putString("ACCOUNT", acc)
                                    .apply()
                                currentUsername = user
                                currentAccount = acc
                                isLoggedIn = true
                            }
                        )
                    } else {
                        MainScreenContainer(
                            onLogout = {
                                prefs.edit().clear().apply()
                                currentUsername = null
                                currentAccount = null
                                isBackendPollingEnabled.value = false
                                isLoggedIn = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreenContainer(onLogout: () -> Unit) {
    val navController = androidx.navigation.compose.rememberNavController()
    var selectedItem by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF121212)) {

                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") },
                    selected = selectedItem == 0,
                    onClick = {
                        selectedItem = 0
                        navController.navigate("dashboard") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Devices, contentDescription = null) },
                    label = { Text("Frota") },
                    selected = selectedItem == 1,
                    onClick = {
                        selectedItem = 1
                        navController.navigate("fleet") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Config") },
                    selected = selectedItem == 2,
                    onClick = {
                        selectedItem = 2
                        navController.navigate("settings") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { padding ->
        androidx.navigation.compose.NavHost(navController, startDestination = "dashboard", Modifier.padding(padding)) {
            composable("dashboard") {
                val activity = LocalContext.current as MainActivity
                DashboardScreen(activity::submitToCloud)
            }
            composable("fleet") {
                FleetManagementScreen()
            }
            composable("settings") {
                SettingsManagementScreen(onLogout)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(submitToCloud: (String, String) -> Unit) {
    val context = LocalContext.current
    val lifecycleScope = (context as MainActivity).lifecycleScope // Get lifecycleScope from MainActivity
    var showTransferDialog by remember { mutableStateOf(false) }
    var transferNumber by remember { mutableStateOf("") }
    var transferAmount by remember { mutableStateOf("") }
    var countdownSeconds by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White)) {
                Text("Q", color = Color.Black, modifier = Modifier.align(Alignment.Center), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Bem-vindo,", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("Super Net 👑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { /* Notifications */ }) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.LightGray)
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // The Sphere
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFff8a65), Color(0xFFba68c8), Color(0xFFe53935)),
                        tileMode = TileMode.Clamp
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) { /* Glow */ }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Status Chip
        Surface(
            color = Color(0xFF1B3B26),
            shape = CircleShape,
            modifier = Modifier.height(32.dp).padding(horizontal = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Serviço Iniciado", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        
        if (!isAccessibilityEnabled.value) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB00020)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚠️ SERVIÇO DE ACESSIBILIDADE DESATIVADO", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("A app não conseguirá ler o saldo nem confirmar transferências.", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color.White)) {
                        Text("ATIVAR AGORA", color = Color(0xFFB00020), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "Tá liberado pra Netflix! Eu cuido dos pagamentos 📺",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Balance Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("SALDO TOTAL DISPONÍVEL", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                val totalBalanceFormatted = remember(ussdBalances) {
                    val total = ussdBalances.values.sumOf { parseBalanceToMb(it) }
                    formatBalance(total)
                }
                Text(totalBalanceFormatted, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)

                if (ussdBalances.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray.copy(alpha = 0.5f))
                    val sims = remember(context) { getSimInfo(context) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ussdBalances.forEach { (subId, balance) ->
                            val simName = sims.find { it.subscriptionId == subId }?.displayName ?: "SIM $subId"
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = simName.toString(), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                Text(text = balance, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { showTransferDialog = true },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD600)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Agendar transf...", color = Color.Black, fontWeight = FontWeight.Bold, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }

            var showWaitingListDialog by remember { mutableStateOf(false) }
            Button(
                onClick = { showWaitingListDialog = true },
                modifier = Modifier.weight(1f).height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Trsf. Fila", color = Color.White)
            }
            
            if (showWaitingListDialog) {
                AlertDialog(
                    onDismissRequest = { showWaitingListDialog = false },
                    title = { Text("Lista de Espera") },
                    text = {
                        LazyColumn {
                            items(waitingList, key = { it.timestamp }) { item ->
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("${item.amount} MB para ${item.number}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("SIM: ${item.carrierName}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Divider(color = Color.DarkGray)
                                }
                            }
                            if (waitingList.isEmpty()) {
                                item { Text("Sem transferências retidas.", color = MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showWaitingListDialog = false }) { Text("OK", color = Color(0xFFFFD600)) }
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row 2: Consultar Saldo
        val activity = LocalContext.current as? MainActivity
        Button(
            onClick = { activity?.startConsultarTodos() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
            shape = RoundedCornerShape(16.dp),
            enabled = activity?.isConsultingAll?.value != true
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            val btnStatus = activity?.consultationStatus?.value ?: "Consultar Todos"
            Text(btnStatus, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    if (showTransferDialog) {
        AlertDialog(
            onDismissRequest = { if (countdownSeconds == 0) showTransferDialog = false },
            title = { Text(if (countdownSeconds > 0) "A Enviar em $countdownSeconds..." else "Agendar Transferência") },
            text = {
                if (countdownSeconds > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp), color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Pode cancelar agora se quiser.", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Column {
                        OutlinedTextField(
                            value = transferNumber,
                            onValueChange = { transferNumber = it },
                            label = { Text("Número") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = transferAmount,
                            onValueChange = { transferAmount = it },
                            label = { Text("Quantidade (MB)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            },
            confirmButton = {
                if (countdownSeconds == 0) {
                    Button(onClick = {
                        if (transferNumber.isNotBlank() && transferAmount.isNotBlank()) {
                            countdownSeconds = 3
                            lifecycleScope.launch {
                                while (countdownSeconds > 0) {
                                    delay(1000)
                                    countdownSeconds--
                                }
                                submitToCloud(transferNumber, transferAmount)
                                showTransferDialog = false
                                transferNumber = ""
                                transferAmount = ""
                            }
                        }
                    }) { Text("Confirmar") }
                } else {
                    TextButton(onClick = {
                        countdownSeconds = 0
                        showTransferDialog = false
                    }) { Text("CANCELAR", color = Color.Red) }
                }
            }
        )
    }
}

@Composable
fun LoginScreen(onLoginSuccess: (String, String, String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") } // New field
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Famba Automator", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("Conta / Usuário (Ex: SuperNet)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("ID do Aparelho (Ex: Celular1)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (errorMessage.isNotEmpty()) {
                Text(text = errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
            }

                Button(
                    onClick = {
                        if (username.isBlank() || password.isBlank() || account.isBlank()) {
                            errorMessage = "Preencha todos os campos"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = ""
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val response = RetrofitClient.api.loginDevice(LoginRequest(username, password, account))
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (response.success) {
                                        onLoginSuccess(username, password, account)
                                    } else {
                                        errorMessage = response.error ?: "Senha Incorreta"
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    errorMessage = "Erro de conexão: ${e.message}"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                    } else {
                        Text("ENTRAR", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                var showRegisterDialog by remember { mutableStateOf(false) }
                TextButton(onClick = { showRegisterDialog = true }) {
                    Text("NÃO TEM CONTA? CRIAR AGORA", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }

                if (showRegisterDialog) {
                    var regUser by remember { mutableStateOf("") }
                    var regPass by remember { mutableStateOf("") }
                    var regAcc by remember { mutableStateOf("") }
                    var regName by remember { mutableStateOf("") }
                    var isRegLoading by remember { mutableStateOf(false) }
                    var regError by remember { mutableStateOf("") }

                    AlertDialog(
                        onDismissRequest = { if (!isRegLoading) showRegisterDialog = false },
                        title = { Text("Criar Nova Conta / Registro") },
                        text = {
                            Column {
                                OutlinedTextField(value = regAcc, onValueChange = { regAcc = it }, label = { Text("Conta (Ex: FambaSales)") }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = regUser, onValueChange = { regUser = it }, label = { Text("ID do Aparelho (Ex: Celular1)") }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = regName, onValueChange = { regName = it }, label = { Text("Nome (Ex: Samsung S21)") }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = regPass, onValueChange = { regPass = it }, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                                if (regError.isNotEmpty()) {
                                    Text(regError, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = !isRegLoading,
                                onClick = {
                                    if (regUser.isBlank() || regPass.isBlank() || regAcc.isBlank()) {
                                        regError = "Preencha os campos obrigatórios"
                                        return@Button
                                    }
                                    isRegLoading = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val resp = RetrofitClient.api.registerDevice(RegisterRequest(regUser, regPass, regName, regAcc))
                                            withContext(Dispatchers.Main) {
                                                isRegLoading = false
                                                if (resp.success) {
                                                    showRegisterDialog = false
                                                    // Auto-fill login
                                                    username = regUser
                                                    account = regAcc
                                                } else {
                                                    regError = resp.error ?: "Erro no registro"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            withContext(Dispatchers.Main) { isRegLoading = false; regError = e.message ?: "Erro" }
                                        }
                                    }
                                }
                            ) { Text("REGISTAR") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRegisterDialog = false }, enabled = !isRegLoading) { Text("CANCELAR") }
                        }
                    )
                }
            }
        }
    }

@Composable
fun FleetManagementScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val response = RetrofitClient.api.getDevices(currentAccount)
                deviceList.clear()
                deviceList.addAll(response.devices)
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao buscar aparelhos: ${e.message}")
            }
            delay(10000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Gestão da Frota", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { 
                val newState = !isBackendPollingEnabled.value
                isBackendPollingEnabled.value = newState 
                // Explicitly sync pause state with server
                (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    try {
                        RetrofitClient.api.togglePause(PauseRequest(currentUsername ?: "", !newState))
                    } catch (e: Exception) {
                         Log.e("MainActivity", "Erro sync pause: ${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isBackendPollingEnabled.value) Color(0xFFF44336) else Color(0xFF4CAF50)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isBackendPollingEnabled.value) "PARAR SINCRONIA" else "INICIAR SINCRONIA",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Dispositivos na Conta", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(deviceList, key = { it.username }) { device ->
                DeviceCard(device)
            }
            if (deviceList.isEmpty()) {
                item {
                    Text("Nenhum outro aparelho online", modifier = Modifier.padding(16.dp), color = Color.DarkGray)
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: Device) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (device.paused) Color.Gray else Color(0xFF4CAF50)))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name ?: device.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(if (device.paused) "Pausado" else "Online", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                
                // --- PAUSE/RESUME BUTTON ---
                val scope = rememberCoroutineScope()
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                RetrofitClient.api.togglePause(PauseRequest(device.username, !device.paused))
                                withContext(Dispatchers.Main) {
                                    // Local optimistic update
                                    val idx = deviceList.indexOfFirst { it.username == device.username }
                                    if (idx != -1) {
                                        deviceList[idx] = deviceList[idx].copy(paused = !device.paused)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Erro ao pausar: ${e.message}")
                            }
                        }
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(if (device.paused) "RETOMAR" else "PAUSAR", color = if (device.paused) Color(0xFF4CAF50) else Color.Red, style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(device.balance ?: "0 MB", fontWeight = FontWeight.ExtraBold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFFFD600))
                    Text("${device.battery}%", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
fun SettingsManagementScreen(onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Definições", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text("v1.5.0", color = Color.DarkGray)
        }
        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
        ) {
            Text("SAIR DA CONTA", fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text("Aparelho Registado como:", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
        Text(currentUsername ?: "Desconhecido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

fun parseBalanceToMb(balanceStr: String): Double {
    return try {
        val normalized = balanceStr.uppercase().replace(",", ".")
        val value = normalized.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        when {
            normalized.contains("GB") -> value * 1024.0
            normalized.contains("KB") -> value / 1024.0
            else -> value
        }
    } catch (_: Exception) { 0.0 }
}

fun formatBalance(mbValue: Double): String {
    return if (mbValue >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mbValue / 1024.0)
    } else if (mbValue > 0) {
        String.format(Locale.US, "%.0f MB", mbValue)
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

    if (response.contains("saldo", ignoreCase = true) || response.contains("extrato", ignoreCase = true)) {
        return "0 MB"
    }
    return null
}

fun checkVodacomBalance(context: Context, subId: Int, onResult: (String) -> Unit) {
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    val subSpecificManager = telephonyManager.createForSubscriptionId(subId)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        } else {
            onResult("API 26+ Requerido")
        }
    } else onResult("Sem permissão")
}

fun getSimInfo(context: Context): List<SubscriptionInfo> {
    val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    return (if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
        subscriptionManager.activeSubscriptionInfoList ?: emptyList()
    } else emptyList())
}
