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
import android.view.WindowManager
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

class MainActivity : ComponentActivity() {
    
    private val ussdBalances = mutableStateMapOf<Int, String>()
    private var isConsultingAll = mutableStateOf(false)
    private var consultationStatus = mutableStateOf("Consultar Todos")
    private var pendingSims = mutableListOf<SubscriptionInfo>()
    private var currentSimId: Int? = null

    // Backend Job tracking
    private var currentBackendJobId: Int? = null
    private var isPollingPaused = false
    private val isBackendPollingEnabled = mutableStateOf(false)
    private val connectionLogs = mutableStateListOf<String>()
    
    // Cloud Fleet State
    private val deviceList = mutableStateListOf<Device>()

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
        
        var currentUsername: String? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isLoggedIn by remember { mutableStateOf(currentUsername != null) }

                    if (!isLoggedIn) {
                        LoginScreen(
                            onLoginSuccess = { user, pass ->
                                prefs.edit().putString("USERNAME", user).putString("PASSWORD", pass).apply()
                                currentUsername = user
                                isLoggedIn = true
                            }
                        )
                    } else {
                        MainScreenContainer(
                            onLogout = {
                                prefs.edit().clear().apply()
                                currentUsername = null
                                isBackendPollingEnabled.value = false
                                isLoggedIn = false
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MainScreenContainer(onLogout: () -> Unit) {
        val navController = androidx.navigation.compose.rememberNavController()
        val items = listOf("dashboard", "fleet", "settings")
        var selectedItem by remember { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF121212)) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination
                    
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
                        label = { Text("Nuvem") },
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
                    DashboardScreen()
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
    fun DashboardScreen() {
        val context = LocalContext.current
        var showTransferDialog by remember { mutableStateOf(false) }
        var transferNumber by remember { mutableStateOf("") }
        var transferAmount by remember { mutableStateOf("") }
        var countdownSeconds by remember { mutableIntStateOf(0) }
        var isSubmitting by remember { mutableStateOf(false) }


        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Placeholder for Logo
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
                // Inner glow / stars effect placeholder
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Just a subtle effect
                }
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

            Text(
                "Tá liberado pra Netflix! Eu cuido dos pagamentos 📺",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { showTransferDialog = true },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Agendar transf...")
                }
                Button(
                    onClick = { /* Show waiting list log or similar */ },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lista de espera")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Balance Card
            Card(
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Saldo Disponível", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val totalBalanceMb by remember { derivedStateOf { ussdBalances.values.sumOf { parseBalanceToMb(it) } } }
                    val totalBalanceFormatted = formatBalance(totalBalanceMb)
                    
                    Text(totalBalanceFormatted, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                    
                    if (ussdBalances.isNotEmpty()) {
                        Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray.copy(alpha = 0.5f))
                        
                        val sims = remember(context) { getSimInfo(context) }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ussdBalances.forEach { (subId, balance) ->
                                val simName = sims.find { it.subscriptionId == subId }?.displayName ?: "SIM $subId"
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = simName.toString(), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    Text(text = balance, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Transfer Dialog with Countdown
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
                                // Start countdown
                                isSubmitting = true
                                countdownSeconds = 3
                                lifecycleScope.launch {
                                    while (countdownSeconds > 0) {
                                        delay(1000)
                                        countdownSeconds--
                                    }
                                    // Submit to Cloud
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

    private fun submitToCloud(number: String, amount: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                addLog("📤 Enviando pedido cloud: $amount MB -> $number")
                val response = RetrofitClient.api.scheduleTransfer(ScheduleTransferRequest(number, amount))
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

    @Composable
    fun LoginScreen(onLoginSuccess: (String, String) -> Unit) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
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
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Nome do Aparelho (Login)") },
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
                        if (username.isBlank() || password.isBlank()) {
                            errorMessage = "Preencha todos os campos"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = ""
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val response = RetrofitClient.api.loginDevice(LoginRequest(username, password))
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (response.success) {
                                        onLoginSuccess(username, password)
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
            }
        }
    }

    @Composable
    fun FleetManagementScreen() {
        // Polling effect for device list
        LaunchedEffect(Unit) {
            while (true) {
                try {
                    val response = RetrofitClient.api.getDevices()
                    deviceList.clear()
                    deviceList.addAll(response.devices)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Erro ao buscar aparelhos: ${e.message}")
                }
                delay(10000) // Poll every 10 seconds
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Gestão da Frota", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(24.dp))

            // Global Sync Button
            Button(
                onClick = { isBackendPollingEnabled.value = !isBackendPollingEnabled.value },
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
                // Online indicator
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (device.paused) Color.Gray else Color(0xFF4CAF50)))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name ?: device.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(if (device.paused) "Pausado" else "Online", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
            val username = remember { currentUsername ?: "Desconhecido" }
            Text(username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

    // Se a mensagem contém a palavra saldo (é uma resposta de consulta) mas não tem as palavras acima, assumimos 0 MB.
    if (response.contains("saldo", ignoreCase = true) || response.contains("extrato", ignoreCase = true)) {
        return "0 MB"
    }

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
