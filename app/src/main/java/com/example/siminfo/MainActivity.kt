package com.example.siminfo

import java.util.Locale

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
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
import android.content.ClipboardManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {
    private lateinit var sessionManager: SessionManager
    
    var isConsultingAll = mutableStateOf(false)
    var consultationStatus = mutableStateOf("Consultar Todos")
    private var pendingSims = mutableListOf<SubscriptionInfo>()
    private var currentSimId: Int? = null

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
        val info = AppState.activeTransferInfo ?: run {
            Log.e("MainActivity", "handleTransferStatus: No activeTransferInfo found!")
            return
        }


        if (status == "SUCCESS") {
            Log.d("MainActivity", "SUCCESS detected. Recipient: ${info.number}, Amount: ${info.amount}")
            Toast.makeText(this, "Transferência Concluída!", Toast.LENGTH_LONG).show()
            val deviceModel = obtainDeviceModel()
            showTransferNotification(info.number, info.amount, deviceModel)

            // Deduct balance locally
            val currentStr = AppState.ussdBalances[info.simId]
            if (currentStr != null) {
                val valMb = parseBalanceToMb(currentStr)
                val deductMb = parseBalanceToMb(info.amount)
                val newBal = (valMb - deductMb).coerceAtLeast(0.0)
                val newBalStr = String.format(Locale.US, "%.1f MB", newBal)
                AppState.ussdBalances[info.simId] = newBalStr
                AppState.addLog("💰 Saldo debitado: -${info.amount}. Novo saldo: $newBalStr")
            }

            // Sincronizar balance atualizado com o servidor imediatamente
            reportJobStatus("SUCESSO")
            sendHeartbeatNow()

            retryCount = 0
            AppState.pendingTransferAmount.value = null
            AppState.pendingTransferNumber.value = null
            AppState.activeTransferInfo = null
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
            // Preserve the backendJobId when adding to waiting list
            val infoWithId = info.copy(backendJobId = AppState.currentBackendJobId)
            AppState.waitingList.add(infoWithId)
            AppState.activeTransferInfo = null
            retryCount = 0
            AppState.pendingTransferAmount.value = null
            AppState.pendingTransferNumber.value = null
            AppState.currentBackendJobId = null
        }
    }


    private fun startTransferTimeout() {
        cancelTransferTimeout()
        transferTimeoutRunnable = Runnable {
            Log.w("MainActivity", "Transfer Timeout. No success message detected.")
            AppState.activeTransferInfo?.let { attemptRetry(it) }
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
            AppState.ussdBalances[subId] = extracted
            if (isConsultingAll.value) {
                Handler(Looper.getMainLooper()).postDelayed({ triggerNextSim(this) }, 7000) 
            }
        } else {
            AppState.ussdBalances[subId] = extracted
            AppState.lastExtractedBalance.value = extracted
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
            val balanceStr = AppState.ussdBalances[sim.subscriptionId]
            if (balanceStr != null) {
                val mb = parseBalanceToMb(balanceStr)
                sim to mb
            } else null
        }.filter { it.second >= amountMb }
         .maxByOrNull { it.second }?.first
    }


    fun startSmartTransfer(context: Context, amount: String, number: String, backendJobId: Int? = null) {
        val amountMb = amount.toDoubleOrNull() ?: 0.0
        if (amountMb <= 0 || number.isBlank()) {
            Toast.makeText(context, "Preencha número e quantidade válida.", Toast.LENGTH_SHORT).show()
            return
        }

        val bestSim = findBestSimForTransfer(amountMb)
        if (bestSim != null) {
            Toast.makeText(context, "Chip selecionado: ${bestSim.displayName}", Toast.LENGTH_SHORT).show()
            startDataTransfer(context, bestSim, amount, number, backendJobId)
        } else {
            Toast.makeText(context, "Nenhum chip com saldo suficiente (${amount} MB).", Toast.LENGTH_LONG).show()
        }
    }

    fun startDataTransfer(context: Context, info: SubscriptionInfo, amount: String, number: String, backendJobId: Int? = null) {
        if (amount.isBlank() || number.isBlank()) {
            Toast.makeText(context, "Preencha número e quantidade.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d("MainActivity", "Starting Data Transfer: $amount MB to $number from SIM ${info.subscriptionId} (Job: $backendJobId)")
        val transferInfo = QueuedTransfer(info.subscriptionId, info.displayName.toString(), amount, number, backendJobId = backendJobId)
        AppState.activeTransferInfo = transferInfo
        AppState.currentBackendJobId = backendJobId

        retryCount = 0
        startTransferInternal(transferInfo)
    }

    private fun startTransferInternal(info: QueuedTransfer) {
        AppState.pendingTransferAmount.value = info.amount
        AppState.pendingTransferNumber.value = info.number


        
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


    fun submitToCloud(number: String, amount: String, targetDevice: String? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppState.addLog("📤 Enviando pedido cloud: $amount MB -> $number" + if(targetDevice != null) " p/ $targetDevice" else "")
                val response = RetrofitClient.api.scheduleTransfer(
                    ScheduleTransferRequest(number, amount, sessionManager.account ?: "", targetDevice) // Pass account name and targetDevice
                )
                withContext(Dispatchers.Main) {
                    if (response.id != null) {
                        Toast.makeText(this@MainActivity, "Agendado com Sucesso! (ID ${response.id})", Toast.LENGTH_SHORT).show()
                        AppState.addLog("✅ Pedido agendado na nuvem.")
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

    // Use AppState.addLog globally



    fun obtainUniqueDeviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    fun obtainDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        return if (model.startsWith(manufacturer)) {
            model.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        } else {
            manufacturer.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } + " " + model
        }
    }

    private fun startBackendPolling() {
        if (AppState.isBackendPollingEnabled.value && sessionManager.username != null) {
            val intent = Intent(this, PollService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    // Envia pulso imediato após transferência para sincronizar saldo com servidor
    fun sendHeartbeatNow() {
        val user = sessionManager.username ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val battery = (getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager)
                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val totalBalance = AppState.ussdBalances.values.sumOf { parseBalanceToMb(it) }.toString() + " MB"
                RetrofitClient.api.updateDeviceStatus(
                    DeviceStatusRequest(user, totalBalance, !AppState.isBackendPollingEnabled.value, battery)
                )
                AppState.addLog("⚡ Saldo pós-transferência sincronizado: $totalBalance")
                Log.d("MainActivity", "Post-transfer heartbeat sent: $totalBalance")
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro a enviar pulso pós-transferência: ${e.message}")
            }
        }
    }

    private fun reportJobStatus(status: String) {
        val jobId = AppState.currentBackendJobId ?: return
        val user = sessionManager.username ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppState.addLog("Enviando status pro Servidor: $status")
                val response = RetrofitClient.api.updateTransferStatus(UpdateStatusRequest(jobId, status, user))
                AppState.addLog("✅ Servidor confirmou: ${response.message}")
            } catch (e: Exception) {
                AppState.addLog("⚠️ Erro ao atualizar status: ${e.message}")
            } finally {
                AppState.currentBackendJobId = null
                AppState.isPollingPaused = false // Resume polling
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

    private fun showTransferNotification(number: String, amount: String, deviceName: String) {
        val builder = NotificationCompat.Builder(this, "transfer_channel")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Transferência Realizada")
            .setContentText("📱 $deviceName enviou: $amount MB para $number")
            .setStyle(NotificationCompat.BigTextStyle().bigText("📱 Dispositivo: $deviceName\n📶 Quantidade: $amount MB\n🎯 Destino: $number"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
        AppState.isAccessibilityEnabled.value = isEnabled
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleJobIntent(intent)
    }

    private fun handleJobIntent(intent: Intent?) {
        val jobId = intent?.getIntExtra("JOB_ID", -1) ?: -1
        if (jobId != -1) {
            val amount = intent?.getStringExtra("JOB_AMOUNT") ?: ""
            val number = intent?.getStringExtra("JOB_NUMBER") ?: ""
            AppState.isPollingPaused = true
            AppState.addLog("🟢 Executando Pedido #${jobId}")
            startSmartTransfer(this, amount, number, jobId)
        }
    }


    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = SessionManager.getInstance(this)
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

        // Initialize state from persistence
        AppState.isBackendPollingEnabled.value = sessionManager.isPollingEnabled

        startBackendPolling()
        requestIgnoreBatteryOptimizations()
        handleJobIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var isLoggedIn by remember { mutableStateOf(sessionManager.isLoggedIn()) }

                    if (!isLoggedIn) {
                        LoginScreen(
                            onLoginSuccess = { user, pass, acc ->
                                sessionManager.username = user
                                sessionManager.password = pass
                                sessionManager.account = acc
                                isLoggedIn = true
                            }
                        )
                    } else {
                        MainScreenContainer(
                            onLogout = {
                                sessionManager.logout()
                                AppState.isBackendPollingEnabled.value = false
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
fun DashboardScreen(submitToCloud: (String, String, String?) -> Unit) {
    val context = LocalContext.current
    val sessionManager = SessionManager.getInstance(context)
    val lifecycleScope = (context as MainActivity).lifecycleScope // Get lifecycleScope from MainActivity

    var showTransferDialog by remember { mutableStateOf(false) }
    var showWaitingListDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var transferNumber by remember { mutableStateOf("") }
    var transferAmount by remember { mutableStateOf("") }
    var countdownSeconds by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White)) {
                Text("Q", color = Color.Black, modifier = Modifier.align(Alignment.Center), fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Bem-vindo a FambaNet,", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                val deviceName = (context as? MainActivity)?.obtainDeviceModel() ?: "Super Net"
                Text("$deviceName 👑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { /* Notifications */ }) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color.LightGray)
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // --- SERVICE SWITCH (Functional & Faster) ---
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = if (AppState.isBackendPollingEnabled.value) Color(0xFF2E7D32).copy(alpha = 0.1f) else Color(0xFF1C1C1E))
        ) {
            Row(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (AppState.isBackendPollingEnabled.value) "SERVIÇO ATIVO" else "SERVIÇO DESLIGADO",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (AppState.isBackendPollingEnabled.value) Color(0xFF4CAF50) else Color.Gray
                    )
                    Text(
                        text = if (AppState.isBackendPollingEnabled.value) "A processar pedidos..." else "Toque para iniciar",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }

                Switch(
                    checked = AppState.isBackendPollingEnabled.value,
                    onCheckedChange = { isChecked ->
                        AppState.isBackendPollingEnabled.value = isChecked
                        sessionManager.isPollingEnabled = isChecked
                        
                        // Sync with server pause state
                        (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                            try {
                                RetrofitClient.api.togglePause(PauseRequest(sessionManager.username ?: "", !isChecked))
                            } catch (e: Exception) { Log.e("MainActivity", "Error sync pause") }
                        }

                        
                        // AUTO-BALANCE CHECK when starting
                        if (isChecked) {
                            (context as? MainActivity)?.startConsultarTodos()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF4CAF50),
                        checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        if (!AppState.isAccessibilityEnabled.value) {

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
                val totalBalanceFormatted = remember {
                    derivedStateOf {
                        val total = AppState.ussdBalances.values.sumOf { parseBalanceToMb(it) }
                        formatBalance(total)
                    }
                }
                Text(totalBalanceFormatted.value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)

                if (AppState.ussdBalances.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = Color.DarkGray.copy(alpha = 0.5f))
                    val sims = remember(context) { getSimInfo(context) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppState.ussdBalances.keys.sorted().forEach { subId ->
                            val balance = AppState.ussdBalances[subId] ?: ""

                            val simIndex = sims.indexOfFirst { it.subscriptionId == subId } + 1
                            val simName = sims.find { it.subscriptionId == subId }?.displayName ?: "SIM $subId"
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "Chip $simIndex ($simName)", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
                            items(AppState.waitingList, key = { it.timestamp }) { item ->
                                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                    Text("${item.amount} MB para ${item.number}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("SIM: ${item.carrierName}" + (if (item.backendJobId != null) " | Job #${item.backendJobId}" else ""), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                        Button(
                                            onClick = {
                                                AppState.waitingList.remove(item)
                                                (context as? MainActivity)?.startSmartTransfer(context, item.amount, item.number, item.backendJobId)
                                                showWaitingListDialog = false
                                            },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Tentar de Novo", fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = { AppState.waitingList.remove(item) },
                                            modifier = Modifier.weight(1f).height(36.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("Excluir", fontSize = MaterialTheme.typography.labelSmall.fontSize, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Divider(color = Color.DarkGray)
                                }
                            }
                            if (AppState.waitingList.isEmpty()) {
                                item { Text("Sem transferências retidas.", color = MaterialTheme.colorScheme.onSurface) }
                            }
                        }
                    },

                    confirmButton = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (AppState.waitingList.isNotEmpty()) {
                                Button(
                                    onClick = { 
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            try {
                                                val res = RetrofitClient.api.retryFailedJobs(RetryFailedRequest(sessionManager.account ?: ""))
                                                withContext(Dispatchers.Main) {
                                                    if (res.success) {
                                                        AppState.waitingList.clear()
                                                        Toast.makeText(context, "Pedidos devolvidos à fila!", Toast.LENGTH_SHORT).show()
                                                        showWaitingListDialog = false
                                                    } else {
                                                        Toast.makeText(context, "Erro: ${res.error}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                withContext(Dispatchers.Main) { Toast.makeText(context, "Erro de conexão", Toast.LENGTH_SHORT).show() }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Reprocessar Falhas no Servidor", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            TextButton(onClick = { showWaitingListDialog = false }, modifier = Modifier.align(Alignment.End)) { Text("FECHAR", color = Color(0xFFFFD600)) }
                        }
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        // Get activity context safely
        val activity = LocalContext.current as MainActivity

        // Logs & History Section
        Text("Gerenciamento", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.align(Alignment.Start).padding(start = 8.dp))
        Spacer(modifier = Modifier.height(8.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Heartbeat & Sync Row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val user = sessionManager.username ?: return@Button
                            activity.lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    AppState.addLog("⚡ Forçando Heartbeat manual...")
                                    val battery = (activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                    val balance = AppState.ussdBalances.values.sumOf { parseBalanceToMb(it) }.toString() + " MB"
                                    RetrofitClient.api.updateDeviceStatus(DeviceStatusRequest(user, balance, !AppState.isBackendPollingEnabled.value, battery))
                                    withContext(Dispatchers.Main) { Toast.makeText(activity, "Pulso enviado!", Toast.LENGTH_SHORT).show() }
                                } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(activity, "Erro no pulso", Toast.LENGTH_SHORT).show() } }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("💖 Heartbeat", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { activity.startConsultarTodos() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = activity.isConsultingAll.value != true,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(if (activity.isConsultingAll.value) "Consultando..." else "🔄 Sincronizar", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Logs Button (Full Width)
                Button(
                    onClick = { showLogsDialog = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver Histórico de Logs", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (showLogsDialog) {
            AlertDialog(
                onDismissRequest = { showLogsDialog = false },
                title = { Text("Histórico de Conexão") },
                text = {
                    Box(modifier = Modifier.height(400.dp)) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp).fillMaxSize(),
                            reverseLayout = true
                        ) {
                            items(AppState.connectionLogs.asReversed()) { log ->
                                Text(
                                    text = log,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = if (log.contains("🔴") || log.contains("⚠️")) Color.Red else if (log.contains("✅") || log.contains("🟢")) Color(0xFF4CAF50) else Color.LightGray
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLogsDialog = false }) { Text("FECHAR", color = Color(0xFFFFD600)) }
                }
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showTransferDialog) {
        var deviceList by remember { mutableStateOf<List<Device>>(emptyList()) }
        var selectedDevice by remember { mutableStateOf("auto") }
        var dropdownExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            try {
                val res = RetrofitClient.api.getDevices(sessionManager.account)
                deviceList = res.devices
            } catch (e: Exception) { }
        }

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
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val pasted = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (pasted.isNotBlank()) transferNumber = pasted.trim()
                                }) {
                                    Icon(Icons.Default.Notifications, contentDescription = "Colar", tint = Color(0xFFFFD600))
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = transferAmount,
                            onValueChange = { transferAmount = it },
                            label = { Text("Quantidade (MB)") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Dropdown for targetDevice
                        ExposedDropdownMenuBox(
                            expanded = dropdownExpanded,
                            onExpandedChange = { dropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = if (selectedDevice == "auto") "Modo Automático" else deviceList.find { it.username == selectedDevice }?.name ?: selectedDevice,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Aparelho Destino") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Modo Automático (Qualquer online)") },
                                    onClick = {
                                        selectedDevice = "auto"
                                        dropdownExpanded = false
                                    }
                                )
                                deviceList.forEach { device ->
                                    DropdownMenuItem(
                                        text = { 
                                            Column {
                                                Text(device.name ?: device.username, fontWeight = FontWeight.Bold)
                                                Text("Saldo: ${device.balance ?: "0 MB"} | Bateria: ${device.battery}%", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                        },
                                        onClick = {
                                            selectedDevice = device.username
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
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
                                val target = if (selectedDevice == "auto") null else selectedDevice
                                submitToCloud(transferNumber, transferAmount, target)
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
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") } // New field
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? MainActivity

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
                label = { Text("Nome da Conta (Ex: SuperNet)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha da Conta") },
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
                        val trimmedAcc = account.trim()
                        val trimmedPass = password.trim()
                        if (trimmedPass.isBlank() || trimmedAcc.isBlank()) {
                            errorMessage = "Preencha conta e senha"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = ""
                        val deviceId = activity?.obtainUniqueDeviceId() ?: "unknown"
                        val deviceName = activity?.obtainDeviceModel() ?: "Unknown Device"
                        
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val response = RetrofitClient.api.loginDevice(LoginRequest(deviceId, trimmedPass, trimmedAcc, deviceName))
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (response.success) {
                                        onLoginSuccess(deviceId, trimmedPass, trimmedAcc)
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
                    var regPass by remember { mutableStateOf("") }
                    var regAcc by remember { mutableStateOf("") }
                    var isRegLoading by remember { mutableStateOf(false) }
                    var regError by remember { mutableStateOf("") }

                    AlertDialog(
                        onDismissRequest = { if (!isRegLoading) showRegisterDialog = false },
                        title = { Text("Definir Nova Conta") },
                        text = {
                            Column {
                                Text("Aparelho identificado: ${activity?.obtainDeviceModel()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(value = regAcc, onValueChange = { regAcc = it }, label = { Text("Nome da Conta (Ex: FambaSales)") }, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = regPass, onValueChange = { regPass = it }, label = { Text("Escolha uma Senha") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                                if (regError.isNotEmpty()) {
                                    Text(regError, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = !isRegLoading,
                                onClick = {
                                    val trimmedAcc = regAcc.trim()
                                    val trimmedPass = regPass.trim()
                                    if (trimmedPass.isBlank() || trimmedAcc.isBlank()) {
                                        regError = "Preencha conta e senha"
                                        return@Button
                                    }
                                    isRegLoading = true
                                    val deviceId = activity?.obtainUniqueDeviceId() ?: "unknown"
                                    val deviceName = activity?.obtainDeviceModel() ?: "Unknown Device"
                                    
                                    coroutineScope.launch(Dispatchers.IO) {
                                        try {
                                            val resp = RetrofitClient.api.registerDevice(RegisterRequest(deviceId, trimmedPass, deviceName, trimmedAcc))
                                            withContext(Dispatchers.Main) {
                                                isRegLoading = false
                                                if (resp.success) {
                                                    showRegisterDialog = false
                                                    // Auto-fill login
                                                    account = trimmedAcc
                                                    password = trimmedPass
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
    val sessionManager = SessionManager.getInstance(context)

    LaunchedEffect(Unit) {
        while (true) {

            try {
                val accToSearch = sessionManager.account?.trim() ?: ""
                Log.d("FleetQuery", "Searching devices for account: '$accToSearch'")
                val response = RetrofitClient.api.getDevices(accToSearch)
                Log.d("FleetQuery", "Found ${response.devices.size} devices")
                AppState.deviceList.clear()
                AppState.deviceList.addAll(response.devices)
            } catch (e: Exception) {
                Log.e("FleetQuery", "Erro ao buscar aparelhos: ${e.message}")
            }
            delay(10000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Gestão da Frota", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { 
                val newState = !AppState.isBackendPollingEnabled.value

                AppState.isBackendPollingEnabled.value = newState 
                // Explicitly sync pause state with server
                (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
                    try {
                        RetrofitClient.api.togglePause(PauseRequest(sessionManager.username ?: "", !newState))
                    } catch (e: Exception) {
                         Log.e("MainActivity", "Erro sync pause: ${e.message}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(80.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (AppState.isBackendPollingEnabled.value) Color(0xFFF44336) else Color(0xFF4CAF50)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (AppState.isBackendPollingEnabled.value) "PARAR SINCRONIA" else "INICIAR SINCRONIA",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }


        Spacer(modifier = Modifier.height(24.dp))

        Text("Dispositivos na Conta", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(AppState.deviceList, key = { it.username }) { device ->
                DeviceCard(device)
            }
            if (AppState.deviceList.isEmpty()) {
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
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (device.isPaused) Color.Gray else Color(0xFF4CAF50)))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name ?: device.username, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(if (device.isPaused) "Pausado" else "Online", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                
                // --- PAUSE/RESUME BUTTON ---
                val scope = rememberCoroutineScope()
                TextButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            try {
                                RetrofitClient.api.togglePause(PauseRequest(device.username, !device.isPaused))
                                withContext(Dispatchers.Main) {
                                    // Local optimistic update
                                    val idx = AppState.deviceList.indexOfFirst { it.username == device.username }
                                    if (idx != -1) {
                                        AppState.deviceList[idx] = AppState.deviceList[idx].copy(paused = if (!device.isPaused) 1 else 0)
                                    }

                                }
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Erro ao pausar: ${e.message}")
                            }
                        }
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(if (device.isPaused) "RETOMAR" else "PAUSAR", color = if (device.isPaused) Color(0xFF4CAF50) else Color.Red, style = MaterialTheme.typography.labelSmall)
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
        val sessionManager = SessionManager.getInstance(LocalContext.current)
        Text("Aparelho Registado como:", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
        Text(sessionManager.username ?: "Desconhecido", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

    }
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
