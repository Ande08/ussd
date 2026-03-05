package com.example.siminfo

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.Bundle

class USSDService : AccessibilityService() {
    
    private var lastProcessedText = ""
    private var lastEventTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < 1000) return // Debounce fast events
        
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            // Use rootInActiveWindow for a more complete view of the screen
            val nodeInfo = rootInActiveWindow ?: event.source ?: return
            
            // Give UI time to settle
            handler.postDelayed({
                val text = traverseNode(nodeInfo)
                if (text.isBlank() || text == lastProcessedText) {
                    return@postDelayed
                }
                
                lastProcessedText = text
                lastEventTime = System.currentTimeMillis()
                Log.d("USSDService", "Screen text detected: \n$text")

                processUssdLogic(nodeInfo, text)
            }, 800)
        }
    }

    private fun processUssdLogic(nodeInfo: AccessibilityNodeInfo, text: String) {
        val normalizedText = text.lowercase()
        Log.d("USSDService", "Processing normalized text: $normalizedText")

        // 1. Handle Balance Inquiry (Existing)
        if (normalizedText.contains("internet") || normalizedText.contains("saldo")) {
            if (!normalizedText.contains("quantos megas") && !normalizedText.contains("digita o numero")) {
                val intent = Intent("com.example.siminfo.USSD_RESULT")
                intent.putExtra("ussd_text", text)
                sendBroadcast(intent)
            }
        }

        // 2. Handle Automated Transfer Steps (Flexible Matching)
        when {
            (normalizedText.contains("sucesso") || normalizedText.contains("transferiste") || normalizedText.contains("enviado") || normalizedText.contains("concluido") || normalizedText.contains("concluida") || normalizedText.contains("confirmado")) 
            && !normalizedText.contains("transferir megas") && !normalizedText.contains("transferencia de megas") -> {
                Log.d("USSDService", "!!! SUCCESS DETECTED !!! Text: $text")
                val intent = Intent("com.example.siminfo.TRANSFER_STATUS")
                intent.putExtra("status", "SUCCESS")
                intent.putExtra("message", text)
                sendBroadcast(intent)
                handler.postDelayed({ dismissUssd(nodeInfo) }, 1000) // 1s delay to ensure button is clickable
            }
            normalizedText.contains("numero do recipiente") || normalizedText.contains("número do recipiente") || normalizedText.contains("digita o numero") || normalizedText.contains("receptor") -> {
                Log.d("USSDService", "Matching: Number step. Text: $text")
                pendingTransferNumber.value?.let { number ->
                    findAndInput(nodeInfo, number)
                }
            }
            normalizedText.contains("quantos megas") || normalizedText.contains("quantidade") || normalizedText.contains("introduza o valor") || (normalizedText.contains("quantos") && normalizedText.contains("megas")) -> {
                Log.d("USSDService", "Matching: Amount step. Text: $text")
                pendingTransferAmount.value?.let { amount ->
                    findAndInput(nodeInfo, amount)
                }
            }
            normalizedText.contains("insuficiente") || normalizedText.contains("indisponivel") || normalizedText.contains("erro") -> {
                Log.d("USSDService", "Matching: Failure detected. Text: $text")
                val intent = Intent("com.example.siminfo.TRANSFER_STATUS")
                intent.putExtra("status", "FAILURE")
                intent.putExtra("message", text)
                sendBroadcast(intent)
                dismissUssd(nodeInfo)
            }
            normalizedText.contains("servicos de internet") || normalizedText.contains("serviços de internet") -> {
                if (pendingTransferAmount.value != null) {
                    Log.d("USSDService", "Matching: Services step. Text: $text")
                    findAndInput(nodeInfo, "8")
                }
            }
            normalizedText.contains("transferir megas") -> {
                if (pendingTransferAmount.value != null) {
                    Log.d("USSDService", "Matching: Transfer step. Text: $text")
                    findAndInput(nodeInfo, "2")
                }
            }
            normalizedText.contains("saldo") || normalizedText.contains("internet") -> {
                Log.d("USSDService", "Matching: General balance popup or unexpected menu. Text: $text")
                dismissUssd(nodeInfo)
            }
        }
    }

    private fun findAndInput(node: AccessibilityNodeInfo, text: String) {
        Log.d("USSDService", "Finding input field for: $text")
        
        // Find EditText (Multiple Strategies)
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        
        // Strategy A: By ID
        val byId = node.findAccessibilityNodeInfosByViewId("android:id/input_field")
        if (!byId.isNullOrEmpty()) {
            Log.d("USSDService", "EditText found by ID")
            editTexts.addAll(byId)
        }
        
        // Strategy B: By ClassName (Manual)
        if (editTexts.isEmpty()) {
            findNodesByClassName(node, "android.widget.EditText", editTexts)
            if (editTexts.isNotEmpty()) Log.d("USSDService", "EditText found by ClassName")
        }
        
        // Find Send Button (Multiple Strategies)
        var sendButton: AccessibilityNodeInfo? = node.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
        if (sendButton == null) {
            sendButton = node.findAccessibilityNodeInfosByText("Send")?.firstOrNull()
            if (sendButton == null) sendButton = node.findAccessibilityNodeInfosByText("Enviar")?.firstOrNull()
            if (sendButton != null) Log.d("USSDService", "Send button found by Text")
        } else {
            Log.d("USSDService", "Send button found by ID")
        }

        if (editTexts.isNotEmpty()) {
            val editText = editTexts[0]
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.d("USSDService", "Input set text result: $success for value: $text")

            // Short delay between input and click
            handler.postDelayed({
                if (sendButton != null && sendButton.isClickable) {
                    val clickSuccess = sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d("USSDService", "Send button clicked: $clickSuccess")
                } else {
                    Log.e("USSDService", "Send button NOT found or NOT clickable")
                }
            }, 400)
        } else {
            Log.e("USSDService", "CRITICAL: No EditText found on this screen!")
        }
    }

    private fun findNodesByClassName(node: AccessibilityNodeInfo, className: String, result: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString() == className) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByClassName(child, className, result)
            }
        }
    }

    private fun dismissUssd(node: AccessibilityNodeInfo) {
        Log.d("USSDService", "Attempting to dismiss USSD...")
        
        // Strategy 1: Standard OK buttons by View ID
        val okById = node.findAccessibilityNodeInfosByViewId("android:id/button1")
        if (!okById.isNullOrEmpty()) {
            val btn = okById[0]
            if (btn.isClickable) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("USSDService", "Dismissed USSD by ID (button1)")
                return
            }
        }

        // Strategy 2: Common labels (check node and parent)
        val dismissLabels = listOf("OK", "Cancelar", "Cancel", "Rejeitar", "Sair", "Submeter", "Concluir", "Aceitar", "Accept", "Sim")
        for (label in dismissLabels) {
            val list = node.findAccessibilityNodeInfosByText(label)
            for (dismissNode in list) {
                if (dismissNode.isClickable) {
                    dismissNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d("USSDService", "Dismissed USSD by label: $label")
                    return
                } else if (dismissNode.parent?.isClickable == true) {
                    dismissNode.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d("USSDService", "Dismissed USSD by label parent: $label")
                    return
                }
            }
        }

        // Strategy 3: Fallback - Click ANY clickable button found on the screen
        val allButtons = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassName(node, "android.widget.Button", allButtons)
        for (btn in allButtons) {
            if (btn.isClickable) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d("USSDService", "Dismissed USSD by clicking ANY button fallback")
                return
            }
        }
    }

    private fun traverseNode(node: AccessibilityNodeInfo): String {
        var text = ""
        if (node.text != null) {
            text += node.text.toString() + "\n"
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                text += traverseNode(child)
            }
        }
        return text
    }

    override fun onInterrupt() {}
}
