package com.example.siminfo

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class PollService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var sessionManager: SessionManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionManager = SessionManager.getInstance(this)
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
                val username = sessionManager.username
                val account = sessionManager.account

                if (username.isNullOrBlank() || account.isNullOrBlank()) {
                    delay(5000)
                    continue
                }

                // --- Heartbeat (Always send even if paused) ---
                try {
                    val currentBattery = (applicationContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    
                    val totalBalanceStr = AppState.ussdBalances.values.sumOf { parseBalanceToMb(it) }.toString() + " MB"
                    Log.d("PollService", "💖 Heartbeat -> User: $username, Balance: $totalBalanceStr, Paused: ${!AppState.isBackendPollingEnabled.value}")
                    
                    RetrofitClient.api.updateDeviceStatus(
                        DeviceStatusRequest(username, totalBalanceStr, !AppState.isBackendPollingEnabled.value, currentBattery)
                    )
                } catch (e: Exception) {
                    Log.e("PollService", "Erro Heartbeat: ${e.message}")
                }

                // --- Settings Sync (Pause state) ---
                try {
                    val devicesResponse = RetrofitClient.api.getDevices(account)
                    val thisDevice = devicesResponse.devices.find { it.username == username }
                    if (thisDevice != null) {
                        val serverEnabled = !thisDevice.isPaused
                        if (serverEnabled != AppState.isBackendPollingEnabled.value) {
                            Log.d("PollService", "Sincronizando pausa: Servidor diz Ativo=$serverEnabled")
                            AppState.isBackendPollingEnabled.value = serverEnabled
                            sessionManager.isPollingEnabled = serverEnabled
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PollService", "Erro ao sincronizar dispositivos: ${e.message}")
                }

                // --- Job Check (Only if NOT paused) ---
                if (AppState.isBackendPollingEnabled.value && !AppState.isPollingPaused && AppState.currentBackendJobId == null) {
                    try {
                        val response = RetrofitClient.api.getPendingTransfer(PendingRequest(username))
                        if (response.job != null) {
                            val job = response.job
                            Log.d("PollService", "Pedido #${job.id} Recebido!")
                            
                            val intent = Intent(this@PollService, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                putExtra("JOB_ID", job.id)
                                putExtra("JOB_AMOUNT", job.amount)
                                putExtra("JOB_NUMBER", job.number)
                            }
                            
                            acquireWakeLock()
                            startActivity(intent)
                        }
                    } catch (e: Exception) {
                        Log.e("PollService", "Erro Polling: ${e.message}")
                    }
                }

                delay(2000)
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SIMInfo::PollWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
                Log.d("PollService", "WakeLock Acquired")
            }
        } catch (e: Exception) {
            Log.e("PollService", "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("PollService", "WakeLock Released")
            }
        } catch (e: Exception) {
            Log.e("PollService", "Error releasing WakeLock: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("poll_channel", "Sync Service", NotificationManager.IMPORTANCE_HIGH)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }
}

