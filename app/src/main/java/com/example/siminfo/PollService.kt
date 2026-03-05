package com.example.siminfo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class PollService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "poll_channel")
            .setContentTitle("SIM Info Sync")
            .setContentText("Monitorando pedidos em segundo plano...")
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setOngoing(true)
            .build()
        
        startForeground(1001, notification)
        startPolling()
        
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            while (isActive) {
                // Ensure session is loaded (especially if started by BootReceiver)
                if (currentUsername.isNullOrBlank() || currentAccount.isNullOrBlank()) {
                    val prefs = applicationContext.getSharedPreferences("FambaPrefs", Context.MODE_PRIVATE)
                    currentUsername = prefs.getString("USERNAME", null)
                    currentAccount = prefs.getString("ACCOUNT", null)
                }

                if (!isBackendPollingEnabled.value || currentUsername.isNullOrBlank()) {
                    delay(3000)
                    continue
                }

                // Heartbeat
                try {
                    val currentBattery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    
                    val totalBalanceStr = ussdBalances.values.sumOf { parseBalanceToMb(it) }.toString() + " MB"
                    Log.d("PollService", "💖 Heartbeat -> User: $currentUsername, Balance: $totalBalanceStr, Paused: ${!isBackendPollingEnabled.value}")
                    
                    RetrofitClient.api.updateDeviceStatus(
                        DeviceStatusRequest(currentUsername!!, totalBalanceStr, !isBackendPollingEnabled.value, currentBattery)
                    )
                } catch (e: Exception) {
                    Log.e("PollService", "Erro Heartbeat: ${e.message}")
                }

                // Check Jobs
                if (isBackendPollingEnabled.value && !isPollingPaused && currentBackendJobId == null) {
                    try {
                        val response = RetrofitClient.api.getPendingTransfer(PendingRequest(currentUsername!!))
                        if (response.job != null) {
                            val job = response.job
                            Log.d("PollService", "Pedido #${job.id} Recebido!")
                            
                            // Relay to MainActivity or handle here
                            val intent = Intent(this@PollService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra("JOB_ID", job.id)
                                putExtra("JOB_AMOUNT", job.amount)
                                putExtra("JOB_NUMBER", job.number)
                            }
                            startActivity(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("PollService", "Erro Polling: ${e.message}")
                    }
                }
                delay(5000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("poll_channel", "Sync Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
