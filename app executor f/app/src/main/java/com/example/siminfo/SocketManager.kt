package com.example.siminfo

import android.content.Context
import android.content.Intent
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SocketManager {
    private const val TAG = "SocketManager"
    private var socket: Socket? = null
    private var currentUsername: String? = null

    fun connect(context: Context, username: String) {
        if (socket?.connected() == true && currentUsername == username) {
            return
        }

        currentUsername = username
        try {
            // Using the same base URL as Retrofit
            val opts = IO.Options()
            opts.forceNew = true
            opts.reconnection = true
            
            socket = IO.socket("http://144.91.121.85:3003/", opts)

            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Conectado ao WebSocket")
                AppState.addLog("🔌 WebSocket Conectado")
                // Registrar dispositivo na sala privada e sala da conta
                val data = JSONObject()
                data.put("username", username)
                
                // Pegar a conta do SessionManager
                val sessionManager = SessionManager.getInstance(context)
                val account = sessionManager.account
                if (!account.isNullOrBlank()) {
                    data.put("account", account)
                }
                
                socket?.emit("register_device", data)
            }

            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Desconectado do WebSocket")
                AppState.addLog("🔌 WebSocket Desconectado")
            }

            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Erro de conexão WebSocket: ${args[0]}")
            }

            // Ouvir novos jobs em tempo real
            socket?.on("new_job") { args ->
                try {
                    val data = args[0] as JSONObject
                    val jobId = data.getInt("id")
                    val number = data.getString("number")
                    val amount = data.getString("amount")

                    Log.d(TAG, "Novo Job recebido via PUSH: $jobId")
                    AppState.addLog("⚡ PUSH: Novo pedido $jobId ($amount para $number)")

                    // Tentar "agarrar" o pedido no banco de dados antes de abrir a UI
                    val sessionManager = SessionManager.getInstance(context)
                    val loginUser = sessionManager.username ?: return@on
                    
                    val scope = CoroutineScope(Dispatchers.IO)
                    scope.launch {
                        try {
                            val response = RetrofitClient.api.getPendingTransfer(PendingRequest(loginUser, jobId))
                            val claimedJob = response.job
                            
                            if (claimedJob != null && claimedJob.id == jobId) {
                                // Conseguimos o pedido! Abrir a UI imediatamente
                                Log.i(TAG, "Sucesso ao agarrar pedido $jobId via PUSH.")
                                AppState.currentBackendJobId = jobId
                                AppState.isPollingPaused = true
                                
                                withContext(Dispatchers.Main) {
                                    val intent = Intent(context, MainActivity::class.java).apply {
                                        putExtra("JOB_ID", jobId)
                                        putExtra("JOB_AMOUNT", amount)
                                        putExtra("JOB_NUMBER", number)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    }
                                    context.startActivity(intent)
                                }
                            } else {
                                Log.w(TAG, "PUSH: Pedido $jobId já foi pego por outro ou saldo insuficiente.")
                                AppState.addLog("⚡ PUSH: Pedido $jobId ignorado (já pego ou sem saldo)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao agarrar pedido via PUSH: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar PUSH job: ${e.message}")
                }
            }

            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Erro na URL do Socket: ${e.message}")
        }
    }

    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
    }
}
