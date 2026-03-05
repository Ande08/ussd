package com.example.siminfo

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Models
data class TransferJob(
    val id: Int,
    val number: String,
    val amount: String,
    val status: String
)

data class PendingResponse(
    val message: String?,
    val job: TransferJob?
)

data class UpdateStatusRequest(
    val id: Int,
    val status: String
)

data class UpdateResponse(
    val message: String?,
    val error: String?
)

// API Interface
interface BackendApi {
    @GET("api/transfer/pending")
    suspend fun getPendingTransfer(): PendingResponse

    @POST("api/transfer/update")
    suspend fun updateTransferStatus(@Body request: UpdateStatusRequest): UpdateResponse
}

// Retrofit Object
object RetrofitClient {
    // Note: For an emulator, use "http://10.0.2.2:3000/". 
    // For a real device, use your computer's IP or an Ngrok URL.
    // Changing this to the local network IP or ngrok later is required.
    private const val BASE_URL = "http://144.91.121.85:3003/" 

    val api: BackendApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }
}
