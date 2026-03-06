package com.example.siminfo

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.PowerManager
import android.content.Context

class USSDService : AccessibilityService() {
    
    private var lastProcessedText = ""
    private var lastEventTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Broaden the filter to capture System Alert Windows (MMI Errors, General Android Alerts) and USSD Dialogs
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.phone" && pkg != "com.android.systemui" && pkg != "android") {
            return
        }

        acquireWakeLock()
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 800) return // Debounce fast events
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            val nodeInfo = rootInActiveWindow ?: event.source ?: return
            
            // Give UI a tiny moment to settle as per specs ("efficient and silent")
            handler.postDelayed({
                val extractedText = StringBuilder()
                val hasInput = checkInteractiveAndExtractText(nodeInfo, extractedText)
                val fullText = extractedText.toString().trim()

                if (fullText.isBlank() || fullText == lastProcessedText) return@postDelayed
                
                lastProcessedText = fullText
                lastEventTime = System.currentTimeMillis()
                Log.d("USSDService", "Captured Text: \n$fullText | Interactive: $hasInput")

                processUssdLogic(nodeInfo, fullText, hasInput, pkg)
            }, 500)
        }
    }

    /**
     * Recursive scan to extract all text and detect if it's an interactive menu (has EditText)
     */
    private fun checkInteractiveAndExtractText(node: AccessibilityNodeInfo?, outText: StringBuilder): Boolean {
        if (node == null) return false
        var interactive = false

        if (node.className?.toString() == "android.widget.EditText") {
            interactive = true
        }

        if (node.text != null && node.text.isNotBlank()) {
            outText.append(node.text).append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                if (checkInteractiveAndExtractText(child, outText)) {
                    interactive = true
                }
            }
        }
        return interactive
    }

    private fun processUssdLogic(nodeInfo: AccessibilityNodeInfo, text: String, isInteractive: Boolean, packageName: String) {
        val normalizedText = text.lowercase()

        // 1. Success Detection — Only on non-interactive (final result) screens
        if (!isInteractive) {
            val pendingNum = AppState.pendingTransferNumber.value
            val isTransferActive = AppState.activeTransferInfo != null
            val isSuccessKeyword = listOf(
                "transferiste com sucesso", "enviado com sucesso", 
                "transferido com sucesso", "realizada com sucesso",
                "transferencia efectuada", "transferencia realizada",
                "operacao concluida", "operação concluida",
                "validos por 24h", "para o numero"
            ).any { normalizedText.contains(it) }

            var finalSuccess = false

            if (isTransferActive && pendingNum != null) {
                // Durante transferência: EXIGIR o número do recipiente no texto
                val matchedNum = normalizedText.contains(pendingNum)
                if (matchedNum && (isSuccessKeyword || normalizedText.contains("sucesso") || normalizedText.contains("transferiste"))) {
                    Log.d("USSDService", "!!! TRANSFER SUCCESS CONFIRMED !!! (text: $text, matchedNum: $pendingNum)")
                    finalSuccess = true
                }
            } else {
                // Apenas palavras-chave muito específicas se não houver transferência ativa (ex: consulta de saldo)
                // Evitamos "sucesso" sozinho aqui para não dar falso positivo em menus
                if (isSuccessKeyword) {
                    Log.d("USSDService", "!!! GENERIC SUCCESS DETECTED !!! (text: $text)")
                    finalSuccess = true
                }
            }

            if (finalSuccess) {
                broadcastStatus("SUCCESS", text)
                autoDismiss(nodeInfo)
                return
            }
        }

        // 2. Failure Patterns
        val failurePatterns = listOf(
            "insuficiente", "saldo insuficiente", "invalido", "inválido",
            "falha", "erro", "nao foi possivel", "não foi possivel",
            "lamentamos", "indisponivel", "indisponível",
            "tente novamente", "incorrecto", "incorreto",
            "nao tem", "não tem", "nao possui", "não possui",
            "numero invalido", "numero incorreto", "servico indisponivel"
        )
        val isFailure = failurePatterns.any { normalizedText.contains(it) }
        if (isFailure) {
            Log.d("USSDService", "!!! FAILURE DETECTED !!! (text: $text)")
            broadcastStatus("FAILURE", text)
            autoDismiss(nodeInfo)
            return
        }

        // 2.b MMI Errors and Generic System UI Alerts (Anti-Lock Mechanism)
        val genericSystemAlerts = listOf(
            "problema de conexao ou codigo mmi invalido", "mmi",
            "connection problem or invalid mmi code", "invalid mmi",
            "rede movel nao disponivel", "mobile network not available",
            "emergencia apenas", "emergency calls only",
            "erro de rede", "network error", "limite alcançado", "tentativas excedidas"
        )
        val isSystemAlert = genericSystemAlerts.any { normalizedText.contains(it) }
        if (isSystemAlert) {
             Log.w("USSDService", "!!! ANTI-LOCK DEPLOYED: SYSTEM ALERT DETECTED !!! (text: $text)")
             broadcastStatus("FAILURE", "Alerta de Sistema: $text")
             autoDismiss(nodeInfo)
             return
        }

        // 3. Intermediate Steps (Only if interactive)
        if (isInteractive) {
            when {
                normalizedText.contains("numero do recipiente") || normalizedText.contains("digita o numero") || normalizedText.contains("receptor") -> {
                    AppState.pendingTransferNumber.value?.let { findAndInput(nodeInfo, it) }
                }
                normalizedText.contains("quantos megas") || normalizedText.contains("quantidade") || normalizedText.contains("introduza o valor") -> {
                    AppState.pendingTransferAmount.value?.let { findAndInput(nodeInfo, it) }
                }
                normalizedText.contains("servicos de internet") || normalizedText.contains("serviços de internet") -> {
                    if (AppState.pendingTransferAmount.value != null) findAndInput(nodeInfo, "8")
                }
                normalizedText.contains("transferir megas") -> {
                    if (AppState.pendingTransferAmount.value != null) findAndInput(nodeInfo, "2")
                }
            }
        } else {
            // Final response message but no specific success/error caught yet?
            // Broadcast as generic result for logs
            broadcastResult(text)
            autoDismiss(nodeInfo)
        }
    }

    private fun broadcastStatus(status: String, message: String) {
        val intent = Intent("com.example.siminfo.TRANSFER_STATUS")
        intent.putExtra("status", status)
        intent.putExtra("message", message)
        sendBroadcast(intent)
        
        // After reporting status, we may release the lock soon
        handler.postDelayed({ releaseWakeLock() }, 3000)
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "SIMInfo::USSDWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 1000L /* 2 minutes timeout */)
                Log.d("USSDService", "WakeLock Acquired for USSD session")
            }
        } catch (e: Exception) {
            Log.e("USSDService", "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("USSDService", "WakeLock Released")
            }
        } catch (e: Exception) {
            Log.e("USSDService", "Error releasing WakeLock: ${e.message}")
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun broadcastResult(text: String) {
        val intent = Intent("com.example.siminfo.USSD_RESULT")
        intent.putExtra("ussd_text", text)
        sendBroadcast(intent)
    }

    private fun findAndInput(node: AccessibilityNodeInfo, text: String, attempt: Int = 1) {
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassName(node, "android.widget.EditText", editTexts)
        
        val sendLabels = listOf("Send", "Enviar", "Enviar ", "Submeter")
        var sendButton: AccessibilityNodeInfo? = null
        for (label in sendLabels) {
            sendButton = node.findAccessibilityNodeInfosByText(label)?.firstOrNull()
            if (sendButton != null) break
        }
        if (sendButton == null) {
            sendButton = node.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
        }

        if (editTexts.isNotEmpty()) {
            val editText = editTexts[0]
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (success) {
                handler.postDelayed({
                    sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }, 400)
            } else if (attempt < 3) {
                handler.postDelayed({ findAndInput(node, text, attempt + 1) }, 500)
            }
        } else if (attempt < 3) {
            Log.d("USSDService", "EditText not found, retry attempt $attempt...")
            handler.postDelayed({
                val newNode = rootInActiveWindow ?: node
                findAndInput(newNode, text, attempt + 1)
            }, 600)
        } else {
            Log.e("USSDService", "Failed to find EditText after 3 attempts")
        }
    }

    private fun findNodesByClassName(node: AccessibilityNodeInfo, className: String, result: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString() == className) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodesByClassName(it, className, result) }
        }
    }

    private fun autoDismiss(node: AccessibilityNodeInfo) {
        Log.d("USSDService", "Auto-dismissing dialog...")
        handler.postDelayed({
            // Target the absolute root to ensure dialog buttons aren't outside the current node scope
            val rootNode = rootInActiveWindow ?: node

            // 1. Prioritize standard Android system buttons first
            val standardButtons = listOf("android:id/button1", "android:id/button2", "android:id/button3")
            for (btnId in standardButtons) {
                rootNode.findAccessibilityNodeInfosByViewId(btnId)?.firstOrNull()?.let {
                    if (it.isClickable) {
                        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return@postDelayed
                    }
                }
            }

            // 2. Fallback to localized text labels
            val dismissLabels = listOf("OK", "Ok", "ok", "Fechar", "Aceitar", "Accept", "Concluir", "Sair", "Cancel", "Cancelar")
            for (label in dismissLabels) {
                val list = rootNode.findAccessibilityNodeInfosByText(label)
                for (dismissNode in list) {
                    if (dismissNode.isClickable) {
                        dismissNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return@postDelayed
                    } else if (dismissNode.parent?.isClickable == true) {
                        dismissNode.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return@postDelayed
                    }
                }
            }
            
            // 3. Last resort fallback to original node just in case
            node.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, 1000) // Slightly increased to ensure dialog is completely drawn by OS before clicking
    }

    override fun onInterrupt() {}
}
