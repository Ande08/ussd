package com.example.fambapay

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissions()
        createNotificationChannel()

        setContent {
            FambaPayTheme {
                MainScreen()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "FambaPay Notifications"
            val descriptionText = "Channel for payment confirmations"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel("famba_pay_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sharedPrefs = context.getSharedPreferences("FambaPayPrefs", Context.MODE_PRIVATE)

    var isServiceRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>("App Iniciado. Aguardando mensagens...") }
    val targetAmounts = remember { 
        mutableStateListOf(*sharedPrefs.getStringSet("target_amounts", emptySet())?.toTypedArray() ?: emptyArray()) 
    }
    var newAmount by remember { mutableStateOf("") }

    // Broadcast Receiver for Logs
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra("log_msg")?.let { msg ->
                    logs.add(msg)
                }
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter("FAMBA_PAY_LOGS"))
        
        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FambaPay - M-Pesa & e-Mola") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status do Serviço", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(12.dp),
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = if (isServiceRunning) Color.Green else Color.Red
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isServiceRunning) "ONLINE (Monitorando SMS)" else "OFFLINE")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { 
                            isServiceRunning = !isServiceRunning
                            val intent = Intent(context, PaymentService::class.java)
                            if (isServiceRunning) context.startForegroundService(intent)
                            else context.stopService(intent)
                        }
                    ) {
                        Icon(if (isServiceRunning) Icons.Default.Settings else Icons.Default.PlayArrow, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isServiceRunning) "PARAR MONITORAMENTO" else "INICIAR MONITORAMENTO")
                    }
                }
            }

            Text("Valores a Monitorar (MT)", fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newAmount,
                    onValueChange = { newAmount = it },
                    label = { Text("Novo Valor (ex: 200.00)") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (newAmount.isNotBlank() && !targetAmounts.contains(newAmount)) {
                        targetAmounts.add(newAmount)
                        sharedPrefs.edit().putStringSet("target_amounts", targetAmounts.toSet()).apply()
                        newAmount = ""
                    }
                }) {
                    Text("Adicionar")
                }
            }
            
            // List of amounts
            LazyColumn(modifier = Modifier.heightIn(max = 120.dp).fillMaxWidth().padding(bottom = 8.dp)) {
                items(targetAmounts) { amount ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("- $amount MT")
                        TextButton(onClick = { 
                            targetAmounts.remove(amount)
                            sharedPrefs.edit().putStringSet("target_amounts", targetAmounts.toSet()).apply()
                        }) {
                            Text("Remover", color = Color.Red)
                        }
                    }
                }
            }

            Text("Logs de Transações", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.05f),
                shape = MaterialTheme.shapes.small
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logs.asReversed()) { log ->
                        Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FambaPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
