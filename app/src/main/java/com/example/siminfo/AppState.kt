package com.example.siminfo

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * Model for a transfer queued for execution.
 */
data class QueuedTransfer(
    val simId: Int,
    val carrierName: String,
    val amount: String,
    val number: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Global application state centralized for better synchronization across components.
 */
object AppState {
    // UI State accessible by composables and services
    val ussdBalances = mutableStateMapOf<Int, String>()
    val isBackendPollingEnabled = mutableStateOf(false)
    val deviceList = mutableStateListOf<Device>()
    val waitingList = mutableStateListOf<QueuedTransfer>()
    val isAccessibilityEnabled = mutableStateOf(false)
    val connectionLogs = mutableStateListOf<String>()

    // USSD / Job State
    val lastExtractedBalance = mutableStateOf<String?>(null)
    val pendingTransferAmount = mutableStateOf<String?>(null)
    val pendingTransferNumber = mutableStateOf<String?>(null)
    
    var currentBackendJobId: Int? = null
    var isPollingPaused = false
    var activeTransferInfo: QueuedTransfer? = null

    /**
     * Adds a message to the connection logs with a timestamp.
     */
    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$time] $msg"
        if (connectionLogs.size >= 50) connectionLogs.removeAt(0)
        connectionLogs.add(logEntry)
    }
}
